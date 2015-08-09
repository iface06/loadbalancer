package de.as.loadbalancer.server;

import java.io.IOException;
import java.util.*;
import javax.servlet.ServletException;
import javax.servlet.http.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.*;
import org.eclipse.jetty.servlet.*;

public class LoadBalancer {

    private final Server webserver;
    private final Map<String, String> initParams;
    protected BalancerServlet lb = new BalancerServlet();
    protected String PATH_SPEC = "/*";

    public LoadBalancer(int port) {
        this.webserver = new Server(port);
        this.initParams = new HashMap<>();
    }

    public LoadBalancer(int port, Map<String, String> initParams) {
        this.webserver = new Server(port);
        this.initParams = initParams;
    }

    public void start() {
        HandlerCollection handlers = createAndInitHandler();
        webserver.setHandler(handlers);
        try {
            webserver.start();
        } catch (Exception ex) {
            throw new RuntimeException("Error while starting Loadbalancer", ex);
        }
    }

    public void stop() {
        try {
            webserver.stop();
        } catch (Exception ex) {
            throw new RuntimeException("Error while stopping Loadbalancer", ex);
        }
    }

    private HandlerCollection createAndInitHandler() {
        HandlerCollection handlers = createHandler();
        ServletContextHandler context = createContextHandler(handlers);
        ServletHolder holder = createServletHolder();
        context.addServlet(holder, PATH_SPEC);
        return handlers;
    }

    protected ServletHolder createServletHolder() {
        ServletHolder servlet = new ServletHolder(lb);
        servlet.setInitParameters(INIT_PARAMS);
        return servlet;
    }

    protected ServletContextHandler createContextHandler(HandlerCollection handlers) {
        ServletContextHandler context = new ServletContextHandler(handlers, "/", ServletContextHandler.SESSIONS);
        return context;
    }

    public void addBalancerMember(String name, String proxyTo) {
        lb.addBalancerMember(name, proxyTo);
    }

    public void stoppingBalancerMember(String name) {
        lb.stoppingBalancerMember(name);
    }

    public void removeBalancerMember(String name) {
        lb.removeBalancerMember(name);
    }

    public void addHostToWhiteList(String host) {
        lb.getWhiteListHosts().add(host);
    }

    protected HandlerCollection createHandler() {
        HandlerCollection handlers = new HandlerCollection();
        return handlers;
    }

    public static void main(String[] args) throws IOException, Exception {
        runLoadBalancer();
        runWorker(8002);
        runWorker(8003);
    }

    private final static Map<String, String> INIT_PARAMS = new HashMap<>();

    static {
        INIT_PARAMS.put("balancerMember.proxy2.proxyTo", "http://localhost:8002");
        INIT_PARAMS.put("stickySessions", "false");
    }

    private static void runLoadBalancer() throws Exception {
        LoadBalancer s = new LoadBalancer(8001, INIT_PARAMS);
        s.addHostToWhiteList("localhost");
        s.addBalancerMember("proxy3", "http://localhost:8003");
        s.start();

    }

    private static void runWorker(final Integer port) throws Exception {
        Server s = new Server(port);

        Handler handlers = new AbstractHandler() {

            @Override
            public void handle(String string, Request rqst, HttpServletRequest hsr, HttpServletResponse hsr1) throws IOException, ServletException {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
                hsr1.setContentType("text/html;charset=utf-8");
                hsr1.setStatus(HttpServletResponse.SC_OK);
                rqst.setHandled(true);
                hsr1.getWriter().println("<h1>Hello World from Server " + port.toString() + "</h1>");
            }
        };
        s.setHandler(handlers);
        s.start();
    }

}
