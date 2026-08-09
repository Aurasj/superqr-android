package com.superqr.android.vision.v7.modem

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

object V7PackageStream {
    const val HEADER_SIZE=64;const val COMPRESSION_NONE=0;const val COMPRESSION_DEFLATE_RAW=1
    private val MAGIC=byteArrayOf('S'.code.toByte(),'7'.code.toByte(),'P'.code.toByte(),'K'.code.toByte())
    data class Metadata(val filename:String,val mimeType:String,val compressionId:Int,val originalSize:Long,val storedSize:Long,val originalSha256:ByteArray,val metadataBytes:Int)
    fun inspect(headerAndMetadata:ByteArray):Metadata{
        if(headerAndMetadata.size<HEADER_SIZE)throw V7ModemException("truncated package header");for(i in MAGIC.indices)if(headerAndMetadata[i]!=MAGIC[i])throw V7ModemException("invalid package magic");val buffer=ByteBuffer.wrap(headerAndMetadata);buffer.position(4)
        val version=buffer.get().toInt()and 0xFF;val compression=buffer.get().toInt()and 0xFF;val flags=buffer.short.toInt()and 0xFFFF;val filenameLen=buffer.short.toInt()and 0xFFFF;val mimeLen=buffer.short.toInt()and 0xFFFF;val originalSize=buffer.long;val storedSize=buffer.long;val sha=ByteArray(32).also{buffer.get(it)};val expectedCrc=buffer.int.toLong()and 0xFFFF_FFFFL
        if(version!=1||flags!=0)throw V7ModemException("unsupported package version/flags");if(compression !in COMPRESSION_NONE..COMPRESSION_DEFLATE_RAW)throw V7ModemException("unsupported package compression");if(filenameLen !in 1..1024||mimeLen !in 0..255)throw V7ModemException("invalid package metadata lengths");if(originalSize<0||storedSize<0)throw V7ModemException("invalid package sizes")
        val metadataBytes=HEADER_SIZE+filenameLen+mimeLen;if(headerAndMetadata.size<metadataBytes)throw V7ModemException("truncated package metadata");val zeroHeader=headerAndMetadata.copyOfRange(0,HEADER_SIZE);zeroHeader.fill(0,HEADER_SIZE-4,HEADER_SIZE);val crc=CRC32().apply{update(zeroHeader,0,HEADER_SIZE-4);update(headerAndMetadata,HEADER_SIZE,filenameLen+mimeLen)}.value and 0xFFFF_FFFFL;if(crc!=expectedCrc)throw V7ModemException("package metadata CRC32 mismatch")
        val filename=decodeUtf8Strict(headerAndMetadata,HEADER_SIZE,filenameLen,"filename");val mime=decodeUtf8Strict(headerAndMetadata,HEADER_SIZE+filenameLen,mimeLen,"MIME");return Metadata(filename,mime.ifBlank{"application/octet-stream"},compression,originalSize,storedSize,sha,metadataBytes)
    }
    fun decodeTo(input:InputStream,output:OutputStream):Metadata{
        val header=input.readExactly(HEADER_SIZE);val filenameLen=((header[8].toInt()and 0xFF)shl 8)or(header[9].toInt()and 0xFF);val mimeLen=((header[10].toInt()and 0xFF)shl 8)or(header[11].toInt()and 0xFF);if(filenameLen !in 1..1024||mimeLen !in 0..255)throw V7ModemException("invalid package metadata lengths");val metadata=inspect(header+input.readExactly(filenameLen+mimeLen));val limited=LimitedInputStream(input,metadata.storedSize);val body:InputStream=when(metadata.compressionId){COMPRESSION_NONE->limited;COMPRESSION_DEFLATE_RAW->InflaterInputStream(limited,Inflater(true),64*1024);else->throw V7ModemException("unsupported package compression")};val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024);var decodedBytes=0L
        try{while(true){val count=body.read(buffer);if(count<0)break;if(count==0)continue;decodedBytes+=count;if(decodedBytes>metadata.originalSize)throw V7ModemException("decoded package exceeds declared file size");digest.update(buffer,0,count);output.write(buffer,0,count)}}catch(e:V7ModemException){throw e}catch(e:Throwable){throw V7ModemException("package decompression failed: ${e.message?:e.javaClass.simpleName}")}finally{if(body is InflaterInputStream)body.close()}
        if(limited.remaining!=0L)throw V7ModemException("stored package body was truncated");if(decodedBytes!=metadata.originalSize)throw V7ModemException("decoded file size mismatch");if(!MessageDigest.isEqual(digest.digest(),metadata.originalSha256))throw V7ModemException("decoded file SHA-256 mismatch");return metadata
    }
    private fun decodeUtf8Strict(bytes:ByteArray,offset:Int,length:Int,label:String):String=try{Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,offset,length)).toString()}catch(e:CharacterCodingException){throw V7ModemException("invalid UTF-8 package $label")}
    private fun InputStream.readExactly(count:Int):ByteArray{val out=ByteArray(count);var offset=0;while(offset<count){val n=read(out,offset,count-offset);if(n<0)throw EOFException("unexpected end of package stream");if(n>0)offset+=n};return out}
    private class LimitedInputStream(private val input:InputStream,length:Long):InputStream(){var remaining:Long=length;private set;override fun read():Int{if(remaining<=0)return -1;val value=input.read();if(value<0)throw EOFException("truncated stored package body");remaining--;return value};override fun read(buffer:ByteArray,offset:Int,length:Int):Int{if(remaining<=0)return -1;val wanted=minOf(length.toLong(),remaining).toInt();val n=input.read(buffer,offset,wanted);if(n<0)throw EOFException("truncated stored package body");remaining-=n;return n}}
}
