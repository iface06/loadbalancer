package de.as.loadbalancer.server;

 //
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//
//  Documentation of the BalancerServlet:
//  http://www.eclipse.org/jetty/documentation/9.2.8.v20150217/balancer-servlet.html
//
//  
//  ========================================================================
//
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.*;
import javax.servlet.http.*;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.proxy.ProxyServlet;
import org.eclipse.jetty.util.URIUtil;

public class BalancerServlet extends ProxyServlet {

    private static final String BALANCER_MEMBER_PREFIX = "balancerMember.";
    private static final List<String> FORBIDDEN_CONFIG_PARAMETERS;

    static {
        List<String> params = new LinkedList<>();
        params.add("hostHeader");
        params.add("whiteList");
        params.add("blackList");
        FORBIDDEN_CONFIG_PARAMETERS = Collections.unmodifiableList(params);
    }

    private static final List<String> REVERSE_PROXY_HEADERS;

    static {
        List<String> params = new LinkedList<>();
        params.add("Location");
        params.add("Content-Location");
        params.add("URI");
        REVERSE_PROXY_HEADERS = Collections.unmodifiableList(params);
    }

    private static final String JSESSIONID = "jsessionid";
    private static final String JSESSIONID_URL_PREFIX = JSESSIONID + "=";
    private static final Long JSESSION_DEFAULT_DURATION_MS = 3600000l; //3600000 ms => 1h

    private final List<BalancerMember> _balancerMembers = new ArrayList<>();
    private final AtomicLong counter = new AtomicLong();
    private boolean _stickySessions;
    private boolean _proxyPassReverse;

    @Override
    public void init() throws ServletException {
        validateConfig();
        super.init();
        initStickySessions();
        initBalancers();
        initProxyPassReverse();
    }

    public synchronized boolean addBalancerMember(String name, String proxyTo) {
        if (name != null && !name.isEmpty()
                && proxyTo != null && !proxyTo.isEmpty()) {
            return _balancerMembers.add(new BalancerMember(name, proxyTo));
        }
        return false;
    }

    public synchronized boolean stoppingBalancerMember(String name) {
        BalancerMember member = findBalancerMemberByName(name);
        if (member != null) {
            member.stopping();
            return true;
        } else {
            return false;
        }
    }

    public synchronized boolean removeBalancerMember(String name) {
        BalancerMember member = findBalancerMemberByName(name);
        if (!member.isActive() && member.isExpired()) {
            return _balancerMembers.remove(member);
        }
        return false;
    }

    private void validateConfig() throws ServletException {
        for (String initParameterName : Collections.list(getServletConfig().getInitParameterNames())) {
            if (FORBIDDEN_CONFIG_PARAMETERS.contains(initParameterName)) {
                throw new UnavailableException(initParameterName + " not supported in " + getClass().getName());
            }
        }
    }

    private void initStickySessions() {
        _stickySessions = Boolean.parseBoolean(getServletConfig().getInitParameter("stickySessions"));
    }

    private void initBalancers() throws ServletException {
        Set<BalancerMember> members = new HashSet<>();
        for (String balancerName : getBalancerNames()) {
            String memberProxyToParam = BALANCER_MEMBER_PREFIX + balancerName + ".proxyTo";
            String proxyTo = getServletConfig().getInitParameter(memberProxyToParam);
            if (proxyTo == null || proxyTo.trim().length() == 0) {
                throw new UnavailableException(memberProxyToParam + " parameter is empty.");
            }
            members.add(new BalancerMember(balancerName, proxyTo));
        }
        _balancerMembers.addAll(members);
    }

    private void initProxyPassReverse() {
        _proxyPassReverse = Boolean.parseBoolean(getServletConfig().getInitParameter("proxyPassReverse"));
    }

    private Set<String> getBalancerNames() throws ServletException {
        Set<String> names = new HashSet<>();
        for (String initParameterName : Collections.list(getServletConfig().getInitParameterNames())) {
            if (!initParameterName.startsWith(BALANCER_MEMBER_PREFIX)) {
                continue;
            }

            int endOfNameIndex = initParameterName.lastIndexOf(".");
            if (endOfNameIndex <= BALANCER_MEMBER_PREFIX.length()) {
                throw new UnavailableException(initParameterName + " parameter does not provide a balancer member name");
            }

            names.add(initParameterName.substring(BALANCER_MEMBER_PREFIX.length(), endOfNameIndex));
        }
        return names;
    }

    @Override
    protected String rewriteTarget(HttpServletRequest request) {
        BalancerMember balancerMember = selectBalancerMember(request);
        if (_log.isDebugEnabled()) {
            _log.debug("Selected {}", balancerMember);
        }
        String path = request.getRequestURI();
        String query = request.getQueryString();
        if (query != null) {
            path += "?" + query;
        }
        return URI.create(balancerMember.getProxyTo() + "/" + path).normalize().toString();
    }

    private BalancerMember selectBalancerMember(HttpServletRequest request) {
        if (_stickySessions) {
            String name = getBalancerMemberNameFromSessionId(request);
            if (name != null) {
                BalancerMember balancerMember = findBalancerMemberByName(name);
                if (balancerMember != null) {
                    return balancerMember;
                }
            }
        }
        return findActiveBalancer();
    }

    private BalancerMember findActiveBalancer() {
        BalancerMember balancerMember = null;
        int rounds = 0;
        do {
            rounds++;
            int index = (int) (counter.getAndIncrement() % _balancerMembers.size());
            balancerMember = _balancerMembers.get(index);
        } while (!balancerMember.isActive() && rounds <= _balancerMembers.size());
        return balancerMember;
    }

    private BalancerMember findBalancerMemberByName(String name) {
        for (BalancerMember balancerMember : _balancerMembers) {
            if (balancerMember.getName().equals(name)) {
                return balancerMember;
            }
        }
        return null;
    }

    private String getBalancerMemberNameFromSessionId(HttpServletRequest request) {
        String name = getBalancerMemberNameFromSessionCookie(request);
        if (name == null) {
            name = getBalancerMemberNameFromURL(request);
        }
        return name;
    }

    private String getBalancerMemberNameFromSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (JSESSIONID.equalsIgnoreCase(cookie.getName())) {
                    return extractBalancerMemberNameFromSessionId(cookie.getValue());
                }
            }
        }
        return null;
    }

    private String getBalancerMemberNameFromURL(HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        int idx = requestURI.lastIndexOf(";");
        if (idx > 0) {
            String requestURISuffix = requestURI.substring(idx + 1);
            if (requestURISuffix.startsWith(JSESSIONID_URL_PREFIX)) {
                return extractBalancerMemberNameFromSessionId(requestURISuffix.substring(JSESSIONID_URL_PREFIX.length()));
            }
        }
        return null;
    }

    private String extractBalancerMemberNameFromSessionId(String sessionId) {
        int idx = sessionId.lastIndexOf(".");
        if (idx > 0) {
            String sessionIdSuffix = sessionId.substring(idx + 1);
            return sessionIdSuffix.length() > 0 ? sessionIdSuffix : null;
        }
        return null;
    }

    @Override
    protected String filterServerResponseHeader(HttpServletRequest request, Response serverResponse, String headerName, String headerValue) {
        if (_proxyPassReverse && REVERSE_PROXY_HEADERS.contains(headerName)) {
            URI locationURI = URI.create(headerValue).normalize();
            if (locationURI.isAbsolute() && isBackendLocation(locationURI)) {
                StringBuilder newURI = URIUtil.newURIBuilder(request.getScheme(), request.getServerName(), request.getServerPort());
                String component = locationURI.getRawPath();
                if (component != null) {
                    newURI.append(component);
                }
                component = locationURI.getRawQuery();
                if (component != null) {
                    newURI.append('?').append(component);
                }
                component = locationURI.getRawFragment();
                if (component != null) {
                    newURI.append('#').append(component);
                }
                return URI.create(newURI.toString()).normalize().toString();
            }
        }
        return headerValue;
    }

    private boolean isBackendLocation(URI locationURI) {
        for (BalancerMember balancerMember : _balancerMembers) {
            URI backendURI = balancerMember.getBackendURI();
            if (backendURI.getHost().equals(locationURI.getHost())
                    && backendURI.getScheme().equals(locationURI.getScheme())
                    && backendURI.getPort() == locationURI.getPort()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean validateDestination(String host, int port) {
        return true;
    }

    private static class BalancerMember {

        private final String _name;
        private final String _proxyTo;
        private final URI _backendURI;
        private boolean active = true;
        private Date lastActivity;

        public BalancerMember(String name, String proxyTo) {
            _name = name;
            _proxyTo = proxyTo;
            _backendURI = URI.create(_proxyTo).normalize();
        }

        public void stopping() {
            active = false;
        }

        public Date getLastActivity() {
            return lastActivity;
        }

        public void markAsInUse() {
            lastActivity = new Date();
        }

        public boolean isActive() {
            return active;
        }

        public String getName() {
            return _name;
        }

        public String getProxyTo() {
            return _proxyTo;
        }

        public URI getBackendURI() {
            return _backendURI;
        }

        @Override
        public String toString() {
            return String.format("%s[name=%s,proxyTo=%s]", getClass().getSimpleName(), _name, _proxyTo);
        }

        @Override
        public int hashCode() {
            return _name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            BalancerMember that = (BalancerMember) obj;
            return _name.equals(that._name);
        }

        private boolean isExpired() {
            long expireTime = lastActivity.getTime() + JSESSION_DEFAULT_DURATION_MS;
            long now = new Date().getTime();
            return now > expireTime;
        }
    }
}
