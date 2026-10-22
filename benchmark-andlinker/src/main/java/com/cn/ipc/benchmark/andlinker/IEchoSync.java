package com.cn.ipc.benchmark.andlinker;

import com.codezjx.andlinker.annotation.In;
import com.codezjx.andlinker.annotation.RemoteInterface;

@RemoteInterface
public interface IEchoSync {
    String echo(@In String payload);
}
