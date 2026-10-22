package com.cn.ipc.benchmark.andlinker;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import com.codezjx.andlinker.AndLinker;
import com.codezjx.andlinker.AndLinkerBinder;

public final class EchoService extends Service {
    private AndLinkerBinder binder;
    private final IEchoSync sync = new IEchoSync() {
        @Override public String echo(String payload) { return payload; }
    };
    private final IEchoAsync async = new IEchoAsync() {
        @Override public String echo(String payload) { return payload; }
    };

    @Override public void onCreate() {
        super.onCreate();
        AndLinker.enableLogger(false);
        binder = AndLinkerBinder.Factory.newBinder();
        binder.registerObject(sync);
        binder.registerObject(async);
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        binder.unRegisterObject(sync);
        binder.unRegisterObject(async);
        super.onDestroy();
    }
}
