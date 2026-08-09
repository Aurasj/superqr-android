package com.superqr.android.vision.v7.modem

/** Bounded generation receiver. Completed bytes are emitted; no whole file is retained here. */
class V7GenerationReceiver(private val maxActiveGenerations:Int=4){
    enum class Status{DUPLICATE,INNOVATIVE,GENERATION_COMPLETE}
    data class Result(val status:Status,val generationId:Long,val rank:Int,val sourceCount:Int,val completedBytes:ByteArray?=null)
    private data class State(val packet:V7ModemPacket,val decoder:V7DenseXorDecoder)
    private val active=LinkedHashMap<Long,State>();private val completed=HashSet<Long>();private var sessionId:Long?=null;private var totalGenerations:Long?=null
    init{require(maxActiveGenerations in 1..16)}
    fun offer(packet:V7ModemPacket):Result{
        val session=sessionId
        if(session==null){sessionId=packet.sessionId;totalGenerations=packet.totalGenerations}else if(packet.sessionId!=session||packet.totalGenerations!=totalGenerations)throw V7ModemException("packet belongs to another modem session")
        if(completed.contains(packet.generationId))return Result(Status.DUPLICATE,packet.generationId,packet.sourceCount,packet.sourceCount)
        var state=active[packet.generationId]
        if(state==null){if(active.size>=maxActiveGenerations)throw V7ModemException("too many concurrent incomplete generations");state=State(packet,V7DenseXorDecoder(packet.sessionId,packet.generationId,packet.sourceCount,packet.symbolBytes));active[packet.generationId]=state}else{val first=state.packet;if(packet.sourceCount!=first.sourceCount||packet.symbolBytes!=first.symbolBytes||packet.generationPayloadLen!=first.generationPayloadLen)throw V7ModemException("generation parameters changed mid-stream")}
        val innovative=state.decoder.add(packet.symbolId,packet.payload);if(!innovative)return Result(Status.DUPLICATE,packet.generationId,state.decoder.rank,packet.sourceCount);if(!state.decoder.complete)return Result(Status.INNOVATIVE,packet.generationId,state.decoder.rank,packet.sourceCount)
        val bytes=state.decoder.decode(packet.generationPayloadLen.toInt());active.remove(packet.generationId);completed+=packet.generationId;return Result(Status.GENERATION_COMPLETE,packet.generationId,packet.sourceCount,packet.sourceCount,bytes)
    }
    fun reset(){active.clear();completed.clear();sessionId=null;totalGenerations=null}
    val activeGenerationCount:Int get()=active.size
    val completedGenerationCount:Int get()=completed.size
}
