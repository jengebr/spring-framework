/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.http.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.LinkedCaseInsensitiveMap;
import org.springframework.util.StringUtils;

/**
 * {@link ServerHttpRequest} implementation that is based on a {@link HttpServletRequest}.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.0
 */
public class ServletServerHttpRequest implements ServerHttpRequest {

	protected static final Charset FORM_CHARSET = StandardCharsets.UTF_8;


	private final HttpServletRequest servletRequest;

	@Nullable
	private URI uri;

	@Nullable
	private HttpHeaders headers;

	@Nullable
	private ServerHttpAsyncRequestControl asyncRequestControl;


	/**
	 * Construct a new instance of the ServletServerHttpRequest based on the
	 * given {@link HttpServletRequest}.
	 * @param servletRequest the servlet request
	 */
	public ServletServerHttpRequest(HttpServletRequest servletRequest) {
		Assert.notNull(servletRequest, "HttpServletRequest must not be null");
		this.servletRequest = servletRequest;
	}


	/**
	 * Returns the {@code HttpServletRequest} this object is based on.
	 */
	public HttpServletRequest getServletRequest() {
		return this.servletRequest;
	}

	@Override
	public HttpMethod getMethod() {
		return HttpMethod.valueOf(this.servletRequest.getMethod());
	}

	@Override
	public URI getURI() {
		if (this.uri == null) {
			this.uri = initURI(this.servletRequest);
		}
		return this.uri;
	}

	/**
	 * Initialize a URI from the given Servet request.
	 * @param servletRequest the request
	 * @return the initialized URI
	 * @since 6.1
	 */
	public static URI initURI(HttpServletRequest servletRequest) {
		String urlString = null;
		boolean hasQuery = false;
		try {
			StringBuffer url = servletRequest.getRequestURL();
			String query = servletRequest.getQueryString();
			hasQuery = StringUtils.hasText(query);
			if (hasQuery) {
				url.append('?').append(query);
			}
			urlString = url.toString();
			return new URI(urlString);
		}
		catch (URISyntaxException ex) {
			if (!hasQuery) {
				throw new IllegalStateException(
						"Could not resolve HttpServletRequest as URI: " + urlString, ex);
			}
			// Maybe a malformed query string... try plain request URL
			try {
				urlString = servletRequest.getRequestURL().toString();
				return new URI(urlString);
			}
			catch (URISyntaxException ex2) {
				throw new IllegalStateException(
						"Could not resolve HttpServletRequest as URI: " + urlString, ex2);
			}
		}
	}

	@Override
	public HttpHeaders getHeaders() {
		if (this.headers == null) {
			this.headers = new HttpHeaders();

			for (Enumeration<?> names = this.servletRequest.getHeaderNames(); names.hasMoreElements();) {
				String headerName = (String) names.nextElement();
				for (Enumeration<?> headerValues = this.servletRequest.getHeaders(headerName);
						headerValues.hasMoreElements();) {
					String headerValue = (String) headerValues.nextElement();
					this.headers.add(headerName, headerValue);
				}
			}

			// HttpServletRequest exposes some headers as properties:
			// we should include those if not already present
			try {
				MediaType contentType = this.headers.getContentType();
				if (contentType == null) {
					String requestContentType = this.servletRequest.getContentType();
					if (StringUtils.hasLength(requestContentType)) {
						contentType = MediaType.parseMediaType(requestContentType);
						if (contentType.isConcrete()) {
							this.headers.setContentType(contentType);
						}
					}
				}
				if (contentType != null && contentType.getCharset() == null) {
					String requestEncoding = this.servletRequest.getCharacterEncoding();
					if (StringUtils.hasLength(requestEncoding)) {
						Charset charSet = Charset.forName(requestEncoding);
						Map<String, String> params = new LinkedCaseInsensitiveMap<>();
						params.putAll(contentType.getParameters());
						params.put("charset", charSet.toString());
						MediaType mediaType = new MediaType(contentType.getType(), contentType.getSubtype(), params);
						this.headers.setContentType(mediaType);
					}
				}
			}
			catch (InvalidMediaTypeException ex) {
				// Ignore: simply not exposing an invalid content type in HttpHeaders...
			}

			if (this.headers.getContentLength() < 0) {
				int requestContentLength = this.servletRequest.getContentLength();
				if (requestContentLength != -1) {
					this.headers.setContentLength(requestContentLength);
				}
			}
		}

		return this.headers;
	}

	@Override
	public Principal getPrincipal() {
		return this.servletRequest.getUserPrincipal();
	}

	@Override
	public InetSocketAddress getLocalAddress() {
		return new InetSocketAddress(this.servletRequest.getLocalAddr(), this.servletRequest.getLocalPort());
	}

	@Override
	public InetSocketAddress getRemoteAddress() {
		return new InetSocketAddress(this.servletRequest.getRemoteHost(), this.servletRequest.getRemotePort());
	}

	@Override
	public InputStream getBody() throws IOException {
		if (isFormPost(this.servletRequest)) {
			return getBodyFromServletRequestParameters(this.servletRequest);
		}
		else {
			return this.servletRequest.getInputStream();
		}
	}

	@Override
	public ServerHttpAsyncRequestControl getAsyncRequestControl(ServerHttpResponse response) {
		if (this.asyncRequestControl == null) {
			if (!(response instanceof ServletServerHttpResponse servletServerResponse)) {
				throw new IllegalArgumentException(
						"Response must be a ServletServerHttpResponse: " + response.getClass());
			}
			this.asyncRequestControl = new ServletServerHttpAsyncRequestControl(this, servletServerResponse);
		}
		return this.asyncRequestControl;
	}


	private static boolean isFormPost(HttpServletRequest request) {
		String contentType = request.getContentType();
		return (contentType != null && contentType.contains(MediaType.APPLICATION_FORM_URLENCODED_VALUE) &&
				HttpMethod.POST.matches(request.getMethod()));
	}

	/**
	 * Use {@link jakarta.servlet.ServletRequest#getParameterMap()} to reconstruct the
	 * body of a form 'POST' providing a predictable outcome as opposed to reading
	 * from the body, which can fail if any other code has used the ServletRequest
	 * to access a parameter, thus causing the input stream to be "consumed".
	 */
	private static InputStream getBodyFromServletRequestParameters(HttpServletRequest request) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
		Writer writer = new OutputStreamWriter(bos, FORM_CHARSET);

		Map<String, String[]> form = request.getParameterMap();
		for (Iterator<Map.Entry<String, String[]>> entryIterator = form.entrySet().iterator(); entryIterator.hasNext();) {
			Map.Entry<String, String[]> entry = entryIterator.next();
			String name = entry.getKey();
			List<String> values = Arrays.asList(entry.getValue());
			for (Iterator<String> valueIterator = values.iterator(); valueIterator.hasNext();) {
				String value = valueIterator.next();
				writer.write(URLEncoder.encode(name, FORM_CHARSET));
				if (value != null) {
					writer.write('=');
					writer.write(URLEncoder.encode(value, FORM_CHARSET));
					if (valueIterator.hasNext()) {
						writer.write('&');
					}
				}
			}
			if (entryIterator.hasNext()) {
				writer.append('&');
			}
		}
		writer.flush();

		return new ByteArrayInputStream(bos.toByteArray());
	}

}
