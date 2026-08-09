package com.superqr.android.vision.v7.modem

private const val MASK_32 = 0xFFFF_FFFFL

object V7DenseXor {
    private val STEP = 0x9E3779B97F4A7C15uL.toLong(); private val MIX1 = 0xBF58476D1CE4E5B9uL.toLong(); private val MIX2 = 0x94D049BB133111EBuL.toLong(); private val SYMBOL_MIX = 0xD6E8FEB86659FD93uL.toLong()
    fun coefficientWords(sessionId: Long,generationId: Long,symbolId: Long,sourceCount: Int): LongArray {
        require(sourceCount in 1..V7ModemContract.MAX_SOURCE_SYMBOLS); require(sessionId >= 0 && generationId >= 0 && symbolId >= 0); val wordCount=(sourceCount+63) ushr 6
        if (symbolId < sourceCount) return LongArray(wordCount).also { it[(symbolId ushr 6).toInt()] = 1L shl (symbolId.toInt() and 63) }
        var state=0x53514D3700000000L xor ((sessionId and MASK_32) shl 16) xor (generationId and MASK_32) xor ((symbolId and MASK_32) * SYMBOL_MIX); val words=LongArray(wordCount)
        for (index in words.indices) { state += STEP; var z=state; z=(z xor (z ushr 30))*MIX1; z=(z xor (z ushr 27))*MIX2; words[index]=z xor (z ushr 31) }
        val validLastBits=sourceCount-(wordCount-1)*64; if (validLastBits<64) words[wordCount-1]=words.last() and ((1L shl validLastBits)-1L)
        var population=0; for (word in words) population+=java.lang.Long.bitCount(word)
        if (population<2 && sourceCount>1) { val bits=intArrayOf(((symbolId-sourceCount)%sourceCount).toInt(),((symbolId-sourceCount+sourceCount/2+1)%sourceCount).toInt()); for(bit in bits) words[bit ushr 6]=words[bit ushr 6] or (1L shl (bit and 63)) } else if(sourceCount==1) words[0]=1L
        return words
    }
    fun encode(sourceSymbols:Array<ByteArray>,sessionId:Long,generationId:Long,symbolId:Long):ByteArray {
        require(sourceSymbols.isNotEmpty()); val size=sourceSymbols[0].size; require(size>0 && sourceSymbols.all{it.size==size}); val out=ByteArray(size); val words=coefficientWords(sessionId,generationId,symbolId,sourceSymbols.size)
        for(wordIndex in words.indices){var word=words[wordIndex];while(word!=0L){val bit=java.lang.Long.numberOfTrailingZeros(word);val source=sourceSymbols[wordIndex*64+bit];for(offset in out.indices)out[offset]=(out[offset].toInt() xor source[offset].toInt()).toByte();word=word and (word-1)}}
        return out
    }
}

class V7DenseXorDecoder(val sessionId:Long,val generationId:Long,val sourceCount:Int,val symbolBytes:Int){
    private val wordCount=(sourceCount+63) ushr 6;private val masks=arrayOfNulls<LongArray>(sourceCount);private val rows=arrayOfNulls<ByteArray>(sourceCount);private val seen=UInt32Set(1024)
    var rank:Int=0;private set
    val complete:Boolean get()=rank==sourceCount
    init{require(sourceCount in 1..V7ModemContract.MAX_SOURCE_SYMBOLS);require(symbolBytes>0)}
    fun add(symbolId:Long,payload:ByteArray):Boolean{
        require(symbolId in 0..MASK_32);require(payload.size==symbolBytes);if(!seen.add(symbolId))return false;val mask=V7DenseXor.coefficientWords(sessionId,generationId,symbolId,sourceCount);val data=payload.copyOf()
        while(true){val pivot=lowestBit(mask);if(pivot<0)return false;val existing=masks[pivot];if(existing==null){masks[pivot]=mask;rows[pivot]=data;rank++;return true};xorMask(mask,existing);xorBytes(data,rows[pivot]!!)}
    }
    fun decode(generationPayloadLen:Int):ByteArray{
        if(!complete)throw V7ModemException("generation is not full-rank");require(generationPayloadLen in 1..sourceCount*symbolBytes);val reducedMasks=Array(sourceCount){masks[it]!!.copyOf()};val reducedRows=Array(sourceCount){rows[it]!!.copyOf()}
        for(pivot in sourceCount-1 downTo 0){while(true){val upper=highestBitAbove(reducedMasks[pivot],pivot);if(upper<0)break;xorMask(reducedMasks[pivot],reducedMasks[upper]);xorBytes(reducedRows[pivot],reducedRows[upper])};if(!isOneHot(reducedMasks[pivot],pivot))throw V7ModemException("fountain basis reduction failed")}
        val out=ByteArray(generationPayloadLen);var written=0;for(row in reducedRows){val count=minOf(symbolBytes,out.size-written);if(count<=0)break;row.copyInto(out,written,0,count);written+=count};return out
    }
    private fun lowestBit(mask:LongArray):Int{for(index in mask.indices)if(mask[index]!=0L)return index*64+java.lang.Long.numberOfTrailingZeros(mask[index]);return -1}
    private fun highestBitAbove(mask:LongArray,pivot:Int):Int{for(wordIndex in mask.lastIndex downTo 0){var word=mask[wordIndex];val base=wordIndex*64;if(base<=pivot){val keepFrom=pivot-base+1;word=if(keepFrom>=64)0L else word and (-1L shl keepFrom)};if(word!=0L)return base+(63-java.lang.Long.numberOfLeadingZeros(word))};return -1}
    private fun isOneHot(mask:LongArray,pivot:Int):Boolean{for(index in mask.indices){val expected=if(index==pivot ushr 6)1L shl(pivot and 63) else 0L;if(mask[index]!=expected)return false};return true}
    private fun xorMask(target:LongArray,source:LongArray){for(i in 0 until wordCount)target[i]=target[i] xor source[i]}
    private fun xorBytes(target:ByteArray,source:ByteArray){for(i in target.indices)target[i]=(target[i].toInt() xor source[i].toInt()).toByte()}
}

private class UInt32Set(initialCapacity:Int){
    private var table=LongArray(Integer.highestOneBit(maxOf(16,initialCapacity-1)) shl 1);private var size=0
    fun add(value:Long):Boolean{val stored=value+1L;if((size+1)*10>=table.size*7)grow();var slot=mix(value).toInt() and(table.size-1);while(true){val current=table[slot];if(current==0L){table[slot]=stored;size++;return true};if(current==stored)return false;slot=(slot+1)and(table.size-1)}}
    private fun grow(){val old=table;table=LongArray(old.size shl 1);size=0;for(stored in old)if(stored!=0L)add(stored-1L)}
    private fun mix(value:Long):Long{var x=value*0x9E3779B97F4A7C15uL.toLong();x=x xor(x ushr 33);x*=0xC2B2AE3D27D4EB4FuL.toLong();return x xor(x ushr 29)}
}
