package com.cn.ipc.api.hub

import com.cn.ipc.ClientServiceSchema
import kotlin.Int
import kotlin.String
import kotlin.collections.Map

/**
 * Generated transaction schema. Parcelable identities are opaque and do not verify DTO field
 * layouts.
 */
public object IMessageHubServiceIpcSchema {
  public const val DESCRIPTOR: String = "com.cn.ipc.api.hub.IMessageHubService"

  public const val CLIENT_CONTRACT_VERSION: Int = 2

  public val METHOD_SIGNATURES: Map<Int, String> = mapOf(
        1 to
            "mode=oneway;role=request;flags=oneway;params=[string!,string!];return=none;envelope=none",
        2 to "mode=oneway;role=request;flags=oneway;params=[string!];return=none;envelope=none",
        3 to "mode=oneway;role=request;flags=oneway;params=[string!];return=none;envelope=none",
        10 to
            "mode=async;role=request;flags=oneway;params=[string!,string!,string!];return=string!;control=requestId:int64,callback:binder;envelope=callback-code1(requestId:int64,status-v1[string-error]);cancel=11",
        11 to
            "mode=async;role=cancel;flags=oneway;params=[requestId:int64,callback:binder];return=none;envelope=none;request=10",
        12 to
            "mode=async;role=request;flags=oneway;params=[];return=string!;control=requestId:int64,callback:binder;envelope=callback-code1(requestId:int64,status-v1[string-error]);cancel=13",
        13 to
            "mode=async;role=cancel;flags=oneway;params=[requestId:int64,callback:binder];return=none;envelope=none;request=12",
        14 to
            "mode=async;role=request;flags=oneway;params=[];return=string!;control=requestId:int64,callback:binder;envelope=callback-code1(requestId:int64,status-v1[string-error]);cancel=15",
        15 to
            "mode=async;role=cancel;flags=oneway;params=[requestId:int64,callback:binder];return=none;envelope=none;request=14",
        20 to
            "mode=stream;role=subscribe;flags=sync;params=[string!];return=flow<string!>;control=observer:binder;envelope=parcel-exception+subId:int64;events=observer-v2(1:string!,2:complete,3:string-error);unsubscribe=21",
        21 to
            "mode=stream;role=unsubscribe;flags=oneway;params=[subId:int64];return=none;envelope=none;subscribe=20",
      )

  public val CLIENT_SCHEMA: ClientServiceSchema = ClientServiceSchema(DESCRIPTOR,
      CLIENT_CONTRACT_VERSION, METHOD_SIGNATURES)
}
