/*
 * Copyright Splunk Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.splunk.rum;

import static android.os.Looper.getMainLooper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Fail.fail;
import static org.robolectric.Shadows.shadowOf;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.HurlStack;
import com.android.volley.toolbox.RequestFuture;
import com.android.volley.toolbox.StringRequest;
import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.MockWebServer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.Scheduler;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.LEGACY)
public class TracingHurlStackTest {

    @Rule
    public OpenTelemetryRule otelTesting = OpenTelemetryRule.create();
    private TestRequestQueue testQueue;
    private MockWebServer server;

    @Before
    public void setup() {
        //setup Volley with TracingHurlStack
        HurlStack tracingHurlStack = VolleyTracing.create(otelTesting.getOpenTelemetry()).newHurlStack();
        testQueue = TestRequestQueue.create(tracingHurlStack);

        //setup test server
        server = new MockWebServer();
    }

    @Test
    public void success() throws IOException, InterruptedException, ExecutionException, TimeoutException {

        server.enqueue(new MockResponse().setBody("success"));
        server.play();

        URL url = server.getUrl("/success");

        RequestFuture<String> response = RequestFuture.newFuture();
        StringRequest stringRequest = new StringRequest(Request.Method.GET, url.toString(),
                response, response);

        testQueue.addToQueue(stringRequest);

        Scheduler scheduler = shadowOf(getMainLooper()).getScheduler();
        while (!scheduler.advanceToLastPostedRunnable());

        String result = response.get(10, TimeUnit.SECONDS);

        assertThat(server.takeRequest().getPath()).isEqualTo("/success"); //server received request
        assertThat(result).isEqualTo("success");

        List<SpanData> spans = otelTesting.getSpans();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);

        verifyAttributes(span, url, 200L);

    }

    @Test
    public void serverError() throws IOException, InterruptedException {

        server.enqueue(new MockResponse().setResponseCode(500));
        server.play();

        URL url = server.getUrl("/error");

        RequestFuture<String> response = RequestFuture.newFuture();
        StringRequest stringRequest = new StringRequest(Request.Method.GET, url.toString(),
                response, response);

        testQueue.addToQueue(stringRequest);

        Scheduler scheduler = shadowOf(getMainLooper()).getScheduler();
        while (!scheduler.advanceToLastPostedRunnable());

        assertThatThrownBy(() -> response.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(VolleyError.class);

        assertThat(server.takeRequest().getPath()).isEqualTo("/error"); //server received request

        List<SpanData> spans = otelTesting.getSpans();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);

        verifyAttributes(span, url, 500L);


    }

    @Test
    public void connectionError() throws IOException {

        server.enqueue(new MockResponse().setBody("should not be received"));
        server.play();

        URL url = new URL("http://" + server.getHostName() + ":" + findUnusedPort() + "/none");

        RequestFuture<String> response = RequestFuture.newFuture();

        StringRequest stringRequest = new StringRequest(Request.Method.GET, url.toString(),
                response, response);
        stringRequest.setRetryPolicy(new DefaultRetryPolicy(50, 0, 1f));

        testQueue.addToQueue(stringRequest);

        Scheduler scheduler = shadowOf(getMainLooper()).getScheduler();
        while (!scheduler.advanceToLastPostedRunnable());

        //thrown exception type depends on the system, e.g. on MacOS - TimeoutError, on Ubuntu - NoConnectionException
        assertThatThrownBy(() -> response.get(3, TimeUnit.SECONDS)).isInstanceOf(Throwable.class);

        assertThat(server.getRequestCount()).isEqualTo(0); //server received no requests

        List<SpanData> spans = otelTesting.getSpans();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("HTTP GET");

        assertThat(span.getStatus()).isEqualTo(StatusData.error());

        assertThat(span.getEvents())
                .hasSize(1)
                .allSatisfy(e -> e.getName().equals(SemanticAttributes.EXCEPTION_EVENT_NAME));

        verifyAttributes(span, url, null);

        Attributes spanAttributes = span.getAttributes();
        assertThat(spanAttributes.get(SplunkRum.ERROR_TYPE_KEY)).isEqualTo("SocketTimeoutException");
        assertThat(spanAttributes.get(SemanticAttributes.EXCEPTION_TYPE)).isEqualTo("SocketTimeoutException");

    }

    @Test
    public void reusedRequest() throws IOException {

        server.enqueue(new MockResponse().setBody("success1"));
        server.enqueue(new MockResponse().setBody("success2"));
        server.play();

        URL url = server.getUrl("/success");

        StringRequest stringRequest = new StringRequest(Request.Method.GET, url.toString(),
                response -> {
                }, error -> {
        });

        testQueue.addToQueue(stringRequest);
        testQueue.addToQueue(stringRequest);

        Scheduler scheduler = shadowOf(getMainLooper()).getScheduler();
        while (!scheduler.advanceToLastPostedRunnable());
        while (!scheduler.advanceToNextPostedRunnable());

        assertThat(server.getRequestCount()).isEqualTo(2);

        List<SpanData> spans = otelTesting.getSpans();
        assertThat(spans).hasSize(2);

        SpanData firstSpan = spans.get(0);
        verifyAttributes(firstSpan, url, 200L);

        SpanData secondSpan = spans.get(1);
        verifyAttributes(secondSpan, url, 200L);
    }


    //TODO: concurrent tests

    private void verifyAttributes(SpanData span, URL url, Long status) {
        assertThat(span.getName()).isEqualTo("HTTP GET");

        Attributes spanAttributes = span.getAttributes();
        assertThat(spanAttributes.get(SemanticAttributes.HTTP_STATUS_CODE)).isEqualTo(status);
        assertThat(spanAttributes.get(SemanticAttributes.NET_PEER_PORT)).isEqualTo(url.getPort());
        assertThat(spanAttributes.get(SemanticAttributes.NET_PEER_NAME)).isEqualTo(url.getHost());
        assertThat(spanAttributes.get(SemanticAttributes.HTTP_URL)).isEqualTo(url.toString());
        assertThat(spanAttributes.get(SemanticAttributes.HTTP_METHOD)).isEqualTo("GET");
    }

    private int findUnusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            assertThat(socket).isNotNull();
            assertThat(socket.getLocalPort()).isGreaterThan(0);
            return socket.getLocalPort();
        } catch (IOException e) {
            fail("Port is not available");
        }
        return -1;
    }

}