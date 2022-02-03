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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.HTTP_RESPONSE_CONTENT_LENGTH;

import com.android.volley.Header;
import com.android.volley.Request;
import com.android.volley.toolbox.HttpResponse;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;

public class VolleyResponseAttributesExtractorTest {

    @Test
    public void spanDecoration() {
        ServerTimingHeaderParser headerParser = mock(ServerTimingHeaderParser.class);
        when(headerParser.parse("headerValue")).thenReturn(new String[]{"9499195c502eb217c448a68bfe0f967c", "fe16eca542cd5d86"});

        List<Header> responseHeaders = Arrays.asList(
                new Header("Server-Timing", "headerValue"),
                new Header("Content-Length", "101")
        );
        RequestWrapper fakeRequest = new RequestWrapper(mock(Request.class), Collections.emptyMap());
        HttpResponse response = new HttpResponse(200, responseHeaders, "hello".getBytes());

        VolleyResponseAttributesExtractor attributesExtractor = new VolleyResponseAttributesExtractor(headerParser);
        AttributesBuilder attributesBuilder = Attributes.builder();
        attributesExtractor.onStart(attributesBuilder, fakeRequest);
        attributesExtractor.onEnd(attributesBuilder, fakeRequest, response, null);
        Attributes attributes = attributesBuilder.build();

        assertEquals("http", attributes.get(SplunkRum.COMPONENT_KEY));
        assertEquals("9499195c502eb217c448a68bfe0f967c", attributes.get(OkHttpRumInterceptor.LINK_TRACE_ID_KEY));
        assertEquals("fe16eca542cd5d86", attributes.get(OkHttpRumInterceptor.LINK_SPAN_ID_KEY));
        assertEquals(101L, (long) attributes.get(HTTP_RESPONSE_CONTENT_LENGTH));
    }

    @Test
    public void spanDecoration_noLinkingHeader() {
        ServerTimingHeaderParser headerParser = mock(ServerTimingHeaderParser.class);
        when(headerParser.parse(null)).thenReturn(new String[0]);

        RequestWrapper fakeRequest = new RequestWrapper(mock(Request.class), Collections.emptyMap());
        HttpResponse response = new HttpResponse(200, Collections.emptyList(), "hello".getBytes());


        VolleyResponseAttributesExtractor attributesExtractor = new VolleyResponseAttributesExtractor(headerParser);
        AttributesBuilder attributesBuilder = Attributes.builder();
        attributesExtractor.onEnd(attributesBuilder, fakeRequest, response, null);
        attributesExtractor.onStart(attributesBuilder, fakeRequest);
        Attributes attributes = attributesBuilder.build();

        assertEquals("http", attributes.get(SplunkRum.COMPONENT_KEY));
        assertNull(attributes.get(OkHttpRumInterceptor.LINK_TRACE_ID_KEY));
        assertNull(attributes.get(OkHttpRumInterceptor.LINK_SPAN_ID_KEY));
    }

    @Test
    public void spanDecoration_contentLength() {
        ServerTimingHeaderParser headerParser = mock(ServerTimingHeaderParser.class);
        when(headerParser.parse(null)).thenReturn(new String[0]);

        List<Header> responseHeaders = Collections.singletonList(
                new Header("Content-Length", "101")
        );
        RequestWrapper fakeRequest = new RequestWrapper(mock(Request.class), Collections.emptyMap());
        HttpResponse response = new HttpResponse(200, responseHeaders, "hello".getBytes());

        VolleyResponseAttributesExtractor attributesExtractor = new VolleyResponseAttributesExtractor(headerParser);
        AttributesBuilder attributesBuilder = Attributes.builder();
        attributesExtractor.onEnd(attributesBuilder, fakeRequest, response, null);
        attributesExtractor.onStart(attributesBuilder, fakeRequest);
        Attributes attributes = attributesBuilder.build();

        assertEquals("http", attributes.get(SplunkRum.COMPONENT_KEY));
        assertEquals(101L, (long) attributes.get(HTTP_RESPONSE_CONTENT_LENGTH));
    }

    @Test
    public void shouldAddExceptionAttributes() {
        ServerTimingHeaderParser headerParser = mock(ServerTimingHeaderParser.class);
        VolleyResponseAttributesExtractor attributesExtractor = new VolleyResponseAttributesExtractor(headerParser);

        RequestWrapper fakeRequest = new RequestWrapper(mock(Request.class), Collections.emptyMap());
        Exception error = new IOException("failed to make a call");

        AttributesBuilder attributesBuilder = Attributes.builder();
        attributesExtractor.onEnd(attributesBuilder, fakeRequest, null, error);
        attributesExtractor.onStart(attributesBuilder, fakeRequest);
        Attributes attributes = attributesBuilder.build();

        assertEquals(5, attributes.size());
        assertEquals("http", attributes.get(SplunkRum.COMPONENT_KEY));
        assertEquals("IOException", attributes.get(SemanticAttributes.EXCEPTION_TYPE));
        assertEquals("failed to make a call", attributes.get(SemanticAttributes.EXCEPTION_MESSAGE));
        //temporary attributes until the RUM UI/backend can be brought up to date with otel conventions.
        assertEquals("IOException", attributes.get(SplunkRum.ERROR_TYPE_KEY));
        assertEquals("failed to make a call", attributes.get(SplunkRum.ERROR_MESSAGE_KEY));
    }

    //TODO: test like OkHttpClientInterceptor, where TracingHurlStack is registered and some internal volley behaviour is mocked
    // to throw an exception
}