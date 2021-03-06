/*
 * Copyright (c) 2021, the hapjs-platform Project Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hapjs.runtime.inspect;

import android.content.Context;
import android.view.View;
import com.eclipsesource.v8.V8;
import com.eclipsesource.v8.V8Value;
import okhttp3.Interceptor;
import okhttp3.WebSocket;
import org.hapjs.render.PageManager;
import org.hapjs.render.VDomChangeAction;
import org.hapjs.render.jsruntime.JsThread;

public interface InspectorProvider extends PageManager.PageChangedListener {
    final String NAME = "inspector";
    final int CONSOLE_LOG = 1;
    final int CONSOLE_DEBUG = 2;
    final int CONSOLE_INFO = 3;
    final int CONSOLE_WARN = 4;
    final int CONSOLE_ERROR = 5;

    Interceptor getNetworkInterceptor();

    WebSocket.Factory getWebSocketFactory();

    void onJsContextCreated(V8 v8);

    void onJsContextDispose(V8 v8);

    void onAppliedChangeAction(Context context, JsThread jsThread, VDomChangeAction action);

    boolean processInspectRequest(String url, Context context);

    // 后续考虑废弃该接口, 直接通过 js console来输出信息
    void onConsoleMessage(int level, String msg);

    void onBeginLoadJsCode(String uri, String content);

    void onEndLoadJsCode(String uri);

    String handleConsoleMessage(final V8Value v8Array);

    boolean isInspectorReady();

    void setRootView(View view);
}
