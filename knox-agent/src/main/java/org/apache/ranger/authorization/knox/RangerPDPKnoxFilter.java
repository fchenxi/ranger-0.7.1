/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.authorization.knox;

import java.io.IOException;
import java.security.AccessController;
import java.security.Principal;
import java.util.HashSet;
import java.util.Set;

import javax.security.auth.Subject;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.gateway.filter.AbstractGatewayFilter;
import org.apache.hadoop.gateway.security.GroupPrincipal;
import org.apache.hadoop.gateway.security.ImpersonatedPrincipal;
import org.apache.hadoop.gateway.security.PrimaryPrincipal;
import org.apache.ranger.audit.provider.MiscUtil;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;

public class RangerPDPKnoxFilter implements Filter {

	private static final Log LOG = LogFactory.getLog(RangerPDPKnoxFilter.class);

	private static final String KNOX_GATEWAY_JASS_CONFIG_SECTION = "com.sun.security.jgss.initiate";

	private String resourceRole = null;
	private static volatile KnoxRangerPlugin plugin = null;

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		resourceRole = getInitParameter(filterConfig, "resource.role");

		KnoxRangerPlugin me = plugin;

		if(me == null) {
			synchronized (RangerPDPKnoxFilter.class) {
				me = plugin;

				if(me == null) {
					try {
						MiscUtil.setUGIFromJAASConfig(KNOX_GATEWAY_JASS_CONFIG_SECTION);
						LOG.info("LoginUser=" + MiscUtil.getUGILoginUser());
					} catch (Throwable t) {
						LOG.error("Error while setting UGI for Knox Plugin...", t);
					}

					LOG.info("Creating KnoxRangerPlugin");
					plugin = new KnoxRangerPlugin();
					plugin.init();
				}
			}
		}
	}

	private String getInitParameter(FilterConfig filterConfig, String paramName) {
		return filterConfig.getInitParameter(paramName.toLowerCase());
	}

	public void destroy() {
	}

	public void doFilter(ServletRequest request, ServletResponse response,
			FilterChain chain) throws IOException, ServletException {

		String sourceUrl = (String) request
				.getAttribute(AbstractGatewayFilter.SOURCE_REQUEST_CONTEXT_URL_ATTRIBUTE_NAME);
		String topologyName = getTopologyName(sourceUrl);
		String serviceName = getServiceName();

		Subject subject = Subject.getSubject(AccessController.getContext());

		Principal primaryPrincipal = (Principal) subject.getPrincipals(
				PrimaryPrincipal.class).toArray()[0];
		String primaryUser = primaryPrincipal.getName();

		String impersonatedUser = null;
		Object[] impersonations = subject.getPrincipals(
				ImpersonatedPrincipal.class).toArray();
		if (impersonations != null && impersonations.length > 0) {
			impersonatedUser = ((Principal) impersonations[0]).getName();
		}

		String user = (impersonatedUser != null) ? impersonatedUser
				: primaryUser;
		if (LOG.isDebugEnabled()) {
			LOG.debug("Checking access primaryUser: " + primaryUser + ", impersonatedUser: "
					+ impersonatedUser + ", effectiveUser: " + user);
		}

		Object[] groupObjects = subject.getPrincipals(GroupPrincipal.class)
				.toArray();
		Set<String> groups = new HashSet<String>();
		for (Object obj : groupObjects) {
			groups.add(((Principal) obj).getName());
		}

		String clientIp = request.getRemoteAddr();
		String clusterName = plugin.getClusterName();

		if (LOG.isDebugEnabled()) {
			LOG.debug("Checking access primaryUser: " + primaryUser
					+ ", impersonatedUser: " + impersonatedUser
					+ ", effectiveUser: " + user + ", groups: " + groups
					+ ", clientIp: " + clientIp + ", clusterName: "
					+ clusterName);
		}
		RangerAccessRequest accessRequest = new KnoxRangerPlugin.RequestBuilder()
			.service(serviceName)
			.topology(topologyName)
			.user(user)
			.groups(groups)
			.clientIp(clientIp)
			.clusterName(clusterName)
			.build();

		boolean accessAllowed = false;

		if (plugin != null) {
			RangerAccessResult result = plugin.isAccessAllowed(accessRequest);

			accessAllowed = result != null && result.getIsAllowed();
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("Access allowed: " + accessAllowed);
		}

		if (accessAllowed) {
			chain.doFilter(request, response);
		} else {
			sendForbidden((HttpServletResponse) response);
		}
	}

	private void sendForbidden(HttpServletResponse res) {
		sendErrorCode(res, 403);
	}

	private void sendErrorCode(HttpServletResponse res, int code) {
		try {
			res.sendError(code);
		} catch (IOException e) {
			LOG.error("Error while redirecting:", e);
		}
	}

	private String getTopologyName(String requestUrl) {
		if (requestUrl == null) {
			return null;
		}
		String url = requestUrl.trim();
		String[] tokens = url.split("/");
		if (tokens.length > 2) {
			return tokens[2];
		} else {
			return null;
		}
	}

	private String getServiceName() {
		return resourceRole;
	}
}
