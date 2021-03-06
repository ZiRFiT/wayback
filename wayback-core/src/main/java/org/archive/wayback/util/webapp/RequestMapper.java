/*
 *  This file is part of the Wayback archival access software
 *   (http://archive-access.sourceforge.net/projects/wayback/).
 *
 *  Licensed to the Internet Archive (IA) by one or more individual
 *  contributors.
 *
 *  The IA licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.archive.wayback.util.webapp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.archive.wayback.core.UIResults;

/**
 * This class maintains a mapping of RequestHandlers and ShutDownListeners, to
 * allow (somewhat) efficient mapping and delegation of incoming requests to
 * the appropriate RequestHandler.
 *
 * This class uses PortMapper to delegate some of the responsibility of mapping
 * requests received on a particular port, and also allows configuration of a
 * global PRE RequestHandler, which gets first dibs on EVERY incoming request,
 * as well as a global POST RequestHandler, which may attempt to handle any
 * incoming request not handled by the normal RequestHandler mapping.
 *
 * Note: responsibility of running ShutdownListeners has been moved to RequestFilter.
 *
 * @see PortMapper#getRequestHandlerContext(HttpServletRequest)
 *
 * @author brad
 *
 */
public class RequestMapper {

	private static final Logger LOGGER = Logger.getLogger(
			RequestMapper.class.getName());

	// TODO Lines kept for quick reminder of the major change, in case something blows up
	// later. To be cleaned up later. Same goes for other commented out code in this class
	// as to shutdownListener.
//	private ArrayList<ShutdownListener> shutdownListeners = null;

	private HashMap<String, PortMapper> portMap = null;
	private RequestHandler globalPreRequestHandler = null;
	private RequestHandler globalPostRequestHandler = null;

	/**
	 * The name of an attribute for storing the prefix of URL
	 * path corresponding to the {@link RequestHandler} processing
	 * the request.
	 */
	public static final String REQUEST_CONTEXT_PREFIX =
		"webapp-request-context-path-prefix";

	/**
	 * Bean name used to register the special global PRE RequestHandler.
	 */
	public final static String GLOBAL_PRE_REQUEST_HANDLER = "-";
	/**
	 * Bean name used to register the special global POST RequestHandler.
	 */
	public final static String GLOBAL_POST_REQUEST_HANDLER = "+";

	/**
	 * Construct a RequestMapper, for the given RequestHandler objects, on the
	 * specified ServletContext. This method will call setServletContext() on
	 * each RequestMapper, followed immediately by registerPortListener()
	 *
	 * @param requestHandlers Collection of RequestHandlers which handle
	 * requests
	 * @param servletContext the webapp ServletContext where this RequestMapper
	 * is configured.
	 */
	public RequestMapper(Collection<RequestHandler> requestHandlers,
			ServletContext servletContext) {
		portMap = new HashMap<String, PortMapper>();
//		shutdownListeners = new ArrayList<ShutdownListener>();
		LOGGER.info("Registering handlers.");
		for (RequestHandler requestHandler : requestHandlers) {
			requestHandler.setServletContext(servletContext);
//			requestHandler.registerPortListener(this);
			// [Kenji] Moved from AbstractRequestHandler.registerPortListener(). I guess
			// registerPortListner() method is originally meant as a point of customization for
			// how request routing is configured based on RequestHandler properties.
			// IMHO, RequestMapper shall be the central point for request routing, and RequestHandler
			// shall be routing-agnostic. It'll be confusing if each RequestHandler defines its own
			// routing specification.
			// So I moved the following line from registerPortListner(), killing the customization
			// point. Customization shall be done through implementation of RequestMapper (if need arise --
			// probably it'd be better to move to spring-webmvc.) Now it is clear static methods
			// of BeanNameRegistrar are actually part of RequestMapper (routing configuration by
			// beanName is effectively dead feature anyway).
			//BeanNameRegistrar.registerHandler(requestHandler, this);
			registerHandler(requestHandler);
		}
		LOGGER.info("Registering handlers complete.");
	}

//	/**
//	 * Request the shutdownListener object to be notified of ServletContext
//	 * shutdown.
//	 * @param shutdownListener the object which needs to have shutdown() called
//	 * when the ServletContext is destroyed.
//	 */
//	public void addShutdownListener(ShutdownListener shutdownListener) {
//		shutdownListeners.add(shutdownListener);
//	}
	private String vhostKey(String host, int port) {
		final String portStr = port >= 0 ? Integer.toString(port) : "";
		if (host == null) host = "";
		return host + ":" + portStr;
	}
 	/**
	 * Configure the specified RequestHandler to handle ALL incoming requests
	 * before any other normal mapping.
	 * @param requestHandler the global PRE RequestHandler
	 */
	public void addGlobalPreRequestHandler(RequestHandler requestHandler) {
		globalPreRequestHandler = requestHandler;
	}
	/**
	 * Configure the specified RequestHandler to handle ALL incoming requests
	 * after all other normal mapping has been attempted
	 * @param requestHandler the global POST RequestHandler
	 */
	public void addGlobalPostRequestHandler(RequestHandler requestHandler) {
		globalPostRequestHandler = requestHandler;
	}
	/**
	 * Register the RequestHandler to accept requests on the given port, for the
	 * specified host and path.
	 * @param port the integer port on which the RequestHandler gets requests.
	 * @param host the String Host which the RequestHandler matches, or null, if
	 * the RequestHandler should match ALL hosts.
	 * @param path the String path which the RequestHandler matches, or null, if
	 * the RequestHandler should match ALL paths.
	 * @param requestHandler the RequestHandler to register.
	 */
	public void addRequestHandler(int port, String host, String path,
			RequestHandler requestHandler) {
		String key = vhostKey(host, port);
		PortMapper portMapper = portMap.get(key);
		if (portMapper == null) {
			portMapper = new PortMapper(host, port);
			portMap.put(key, portMapper);
		}
		portMapper.addRequestHandler(path, requestHandler);
		// path-segment parameter support
		// trailing ";" in path signifies that this path accepts in-segment
		// parameters. Register requestHandler once more with path without the trailing
		// ";", so that path is routed without in-segment parameters as well.
		// So for example, For path "/save;", requestHadler is registered twice,
		// one for "/save;" and another for "/save".
		if (path != null && path.endsWith(";")) {
			portMapper.addRequestHandler(path.substring(0, path.length() - 1),
				requestHandler);
		}

		LOGGER.info("Registered " + port + "/" +
				(host == null ? "*" : host) + "/" +
				(path == null ? "*" : path) + " --> " +
				requestHandler);
	}

	public RequestHandlerContext mapRequest(HttpServletRequest request) {
		String host = request.getServerName();
		int port = request.getLocalPort();
		String keys[] = {
			vhostKey(host, port),
			vhostKey(null, port),
			vhostKey(host, -1),
			vhostKey(null, -1)
		};
		for (String key : keys) {
			PortMapper portMapper = portMap.get(key);
			if (portMapper != null) {
				RequestHandlerContext rhc = portMapper.getRequestHandlerContext(request);
				if (rhc != null)
					return rhc;
			}
		}

		LOGGER.warning("no matching route found for " + request.getRequestURL());
		// Try to help get user back on track by presenting available routes.
		// Note this won't help with hostname mismatches at the moment.
		// TODO: It'd be better for RequestMapper to expose a service to UI code,
		// rather than RequestMapper setting attribute.
		Set<String> paths = new HashSet<String>();
		for (String key : keys) {
			PortMapper vhost = portMap.get(key);
			if (vhost != null) {
				paths.addAll(vhost.getPaths());
			}
		}
		if (!paths.isEmpty()) {
			// XXX existing code expects ArrayList specifically
			// (wayback-webapp/src/main/webapp/index.jsp)
			request.setAttribute("AccessPointNames", new ArrayList<String>(paths));
		}
		return null;
	}

	/**
	 * Map the incoming request to the appropriate RequestHandler, including
	 * the PRE and POST RequestHandlers, if configured.
	 * @param request the incoming HttpServletRequest
	 * @param response the HttpServletResponse to return data to the client
	 * @return true if a response was returned to the client.
	 * @throws ServletException for usual reasons.
	 * @throws IOException for usual reasons.
	 */
	public boolean handleRequest(HttpServletRequest request,
			HttpServletResponse response) throws IOException, ServletException {
		boolean handled = false;

		// Internally UIResults.forward(), don't handle here
		if (request.getAttribute(UIResults.FERRET_NAME) != null) {
			return false;
		}

		if (globalPreRequestHandler != null) {
			handled = globalPreRequestHandler.handleRequest(request, response);
		}
		if (!handled) {
			RequestHandlerContext handlerContext = mapRequest(request);
			if (handlerContext != null) {
				RequestHandler requestHandler =
					handlerContext.getRequestHandler();
				// need to add trailing "/" iff prefix is not "/":
				String pathPrefix = handlerContext.getPathPrefix();
				if (!pathPrefix.equals("/")) {
					pathPrefix += "/";
				}
				request.setAttribute(REQUEST_CONTEXT_PREFIX, pathPrefix);
				handled = requestHandler.handleRequest(request, response);
			}
		}
		if (!handled) {
			if(globalPostRequestHandler != null) {
				handled = globalPostRequestHandler.handleRequest(request,
						response);
			}
		}

		return handled;
	}

//	/**
//	 * notify all registered ShutdownListener objects that the ServletContext is
//	 * being destroyed.
//	 */
//	public void shutdown() {
//		for (ShutdownListener shutdownListener : shutdownListeners) {
//			try {
//				shutdownListener.shutdown();
//			} catch(Exception e) {
//				LOGGER.severe("failed shutdown"+e.getMessage());
//			}
//		}
//	}

	/**
	 * Extract the request path prefix, as computed at RequestHandler mapping,
	 * from the HttpServletRequest object.
	 *
	 * @param request HttpServlet request object being handled
	 * @return the portion of the original request path which indicated the
	 * RequestHandler, including the trailing '/'.
	 */
	public static String getRequestPathPrefix(HttpServletRequest request) {
		return (String) request.getAttribute(REQUEST_CONTEXT_PREFIX);
	}

	/**
	 * @param request HttpServlet request object being handled
	 * @return the portion of the incoming path within the RequestHandler
	 * handling the request, not including a leading "/", and not including
	 * query arguments.
	 */
	public static String getRequestContextPath(HttpServletRequest request) {
		String prefix = (String) request.getAttribute(REQUEST_CONTEXT_PREFIX);
		String requestUrl = request.getRequestURI();
		if (prefix == null) {
			return requestUrl;
		}
		if (requestUrl.startsWith(prefix)) {
			return requestUrl.substring(prefix.length());
		}
		return requestUrl;
	}

	/**
	 * @param request HttpServlet request object being handled
	 * @return the portion of the incoming path within the RequestHandler
	 * handling the request, not including a leading "/", including query
	 * arguments.
	 */
	public static String getRequestContextPathQuery(HttpServletRequest request) {
		String prefix = (String)request.getAttribute(REQUEST_CONTEXT_PREFIX);
		// HttpServletRequest.getRequestURI() returns path portion of request URL
		// (does not include query part), *undecoded*.
		StringBuilder sb = new StringBuilder(request.getRequestURI());
		String requestUrl = null;
		String query = request.getQueryString();
		if (query != null) {
			requestUrl = sb.append("?").append(query).toString();
		} else {
			requestUrl = sb.toString();
		}
		if (prefix == null) {
			return requestUrl;
		}
		if (requestUrl.startsWith(prefix)) {
			return requestUrl.substring(prefix.length());
		}
		// Fix for access point with missing end slash
		else if (prefix.endsWith("/") && (requestUrl + "/").equals(prefix)) {
			return "";
		}
		return requestUrl;
	}


	// moved from BeanNameRegistrar

	private static final Pattern PORT_PATTERN = Pattern
		.compile("(?<port>[0-9]+):?");
	private static final Pattern PORT_PATH_PATTERN = Pattern
		.compile("(?<port>[0-9]+):(?<path>[-0-9a-zA-Z_.]+;?)");
	private static final Pattern HOST_PORT_PATTERN = Pattern
		.compile("(?<host>[0-9a-z_.-]+):(?<port>[0-9]+):?");
	private static final Pattern HOST_PORT_PATH_PATTERN = Pattern
		.compile("(?<host>[0-9a-z_.-]+):(?<port>[0-9]+):(?<path>[-0-9a-zA-Z_.]+;?)");
	private static final Pattern URI_PATTERN = Pattern
		.compile("(https?://(?<host>[0-9a-z_.-]+))?(?::(?<port>[0-9]+))?/(?<path>[-0-9a-zA-Z_.]+;?)(/.*)");

	/*
	 * matches: 8080 8080:
	 */
	private boolean registerPort(String name, RequestHandler handler) {
		Matcher m = null;
		m = PORT_PATTERN.matcher(name);
		if (m.matches()) {
			int port = Integer.parseInt(m.group("port"));
			addRequestHandler(port, null, null, handler);
			return true;
		}
		return false;
	}

	/*
	 * matches: 8080:blue 8080:fish
	 */
	private boolean registerPortPath(String name,
			RequestHandler handler) {
		Matcher m = null;
		m = PORT_PATH_PATTERN.matcher(name);
		if (m.matches()) {
			int port = Integer.parseInt(m.group("port"));
			addRequestHandler(port, null, m.group("path"), handler);
			return true;
		}
		return false;
	}

	/*
	 * matches: localhost.archive.org:8080 static.localhost.archive.org:8080
	 */
	private boolean registerHostPort(String name,
			RequestHandler handler) {
		Matcher m = null;
		m = HOST_PORT_PATTERN.matcher(name);
		if (m.matches()) {
			int port = Integer.parseInt(m.group("port"));
			addRequestHandler(port, m.group("host"), null, handler);
			return true;
		}
		return false;
	}

	/*
	 * matches: localhost.archive.org:8080:two
	 * static.localhost.archive.org:8080:fish
	 */
	private boolean registerHostPortPath(String name,
			RequestHandler handler) {
		Matcher m = null;
		m = HOST_PORT_PATH_PATTERN.matcher(name);
		if (m.matches()) {
			int port = Integer.parseInt(m.group("port"));
			addRequestHandler(port, m.group("host"), m.group("path"), handler);
			return true;
		}
		return false;
	}

	/*
	 * matches: http://localhost.archive.org:8080/two the port is optional, and
	 * need not be part of the URI if not included, using the internalPort
	 * setting
	 */
	private boolean registerURIPatternPath(String name, int port,
			RequestHandler handler) {
		Matcher m = null;
		m = URI_PATTERN.matcher(name);

		if (m.matches()) {
			String host = m.group("host");
			String portString = m.group("port");

			if (portString != null) {
				port = Integer.parseInt(portString);
			}

			String path = m.group("path");

			addRequestHandler(port, null, path, handler);
			return true;
		}
		return false;
	}

	/**
	 * Extract the RequestHandler objects beanName, parse it, and register the
	 * RequestHandler with the RequestMapper according to the beanNames
	 * semantics.
	 * @param handler The RequestHandler to register
	 */
	public void registerHandler(RequestHandler handler) {

		String name = null;
		int internalPort = 8080;

		if (handler instanceof AbstractRequestHandler) {
			name = ((AbstractRequestHandler)handler).getAccessPointPath();
			internalPort = ((AbstractRequestHandler)handler).getInternalPort();
		}

		if (name == null) {
			name = handler.getBeanName();
		}

		if (name != null) {
			if (name.equals(RequestMapper.GLOBAL_PRE_REQUEST_HANDLER)) {
				LOGGER
					.info("Registering Global-pre request handler:" + handler);
				addGlobalPreRequestHandler(handler);

			} else if (name.equals(RequestMapper.GLOBAL_POST_REQUEST_HANDLER)) {

				LOGGER.info("Registering Global-post request handler:" +
						handler);
				addGlobalPostRequestHandler(handler);

			} else {
				try {

					boolean registered = registerPort(name, handler) ||
							registerPortPath(name, handler) ||
							registerHostPort(name, handler) ||
							registerHostPortPath(name, handler) ||
							registerURIPatternPath(name, internalPort, handler);

					if (!registered) {
						LOGGER.severe("Unable to register (" + name + ")");
					}
				} catch (NumberFormatException e) {
					LOGGER.severe("FAILED parseInt(" + name + ")");
				}
			}
		} else {
			LOGGER.info("Unable to register RequestHandler - null beanName");
		}
//			if(handler instanceof ShutdownListener) {
//				ShutdownListener s = (ShutdownListener) handler;
//				mapper.addShutdownListener(s);
//			}
	}

}
