/*
 * Copyright 2013 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.overlord.dtgov.devsvr.bpm;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;

import javax.naming.InitialContext;
import javax.sql.DataSource;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.jasper.servlet.JspServlet;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.jboss.weld.environment.servlet.BeanManagerResourceBindingListener;
import org.jboss.weld.environment.servlet.Listener;
import org.overlord.commons.dev.server.DevServer;
import org.overlord.commons.dev.server.DevServerEnvironment;
import org.overlord.commons.dev.server.discovery.WebAppModuleFromIDEDiscoveryStrategy;
import org.overlord.commons.dev.server.discovery.WebAppModuleFromMavenDiscoveryStrategy;
import org.overlord.dtgov.jbpm.web.ProcessServlet;
import org.overlord.dtgov.jbpm.web.TaskServlet;
import org.overlord.sramp.governance.Governance;
import org.overlord.sramp.governance.GovernanceServlet;

/**
 * A dev server for DTGov BPM.
 * @author eric.wittmann@redhat.com
 */
public class DTGovBpmDevServer extends DevServer {

    private DataSource ds = null;

    /**
     * Main entry point.
     * @param args
     */
    public static void main(String [] args) throws Exception {
        DTGovBpmDevServer devServer = new DTGovBpmDevServer(args);
        devServer.enableDebug();
        devServer.go();
    }

    /**
     * Constructor.
     * @param args
     */
    public DTGovBpmDevServer(String [] args) {
        super(args);
    }

    /**
     * @see org.overlord.commons.dev.server.DevServer#preConfig()
     */
    @Override
    protected void preConfig() {
        // Add JNDI resources
        try {
            InitialContext ctx = new InitialContext();
            InitialContext jbossCtx = new InitialContext();
            ctx.bind("java:jboss", jbossCtx);
            jbossCtx.bind("datasources", new InitialContext());
            ds = createInMemoryDatasource();
            ctx.bind("java:jboss/datasources/jbpmDS", ds);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @see org.overlord.commons.dev.server.DevServer#createDevEnvironment()
     */
    @Override
    protected DevServerEnvironment createDevEnvironment() {
        return new DTGovBpmDevServerEnvironment(args);
    }

    /**
     * @see org.overlord.commons.dev.server.DevServer#serverPort()
     */
    @Override
    protected int serverPort() {
        return 8083;
    }

    /**
     * @see org.overlord.commons.dev.server.DevServer#addModules(org.overlord.commons.dev.server.DevServerEnvironment)
     */
    @Override
    protected void addModules(DevServerEnvironment environment) {
        environment.addModule("dtgov-war",
                new WebAppModuleFromIDEDiscoveryStrategy(Governance.class),
                new WebAppModuleFromMavenDiscoveryStrategy(Governance.class));
    }

    /**
     * @see org.overlord.commons.dev.server.DevServer#addModulesToJetty(org.overlord.commons.dev.server.DevServerEnvironment, org.eclipse.jetty.server.handler.ContextHandlerCollection)
     */
    @Override
    protected void addModulesToJetty(DevServerEnvironment environment, ContextHandlerCollection handlers) throws Exception {
        /* *********
         * DTGov War
         * ********* */
        ServletContextHandler dtgovWar = new ServletContextHandler(ServletContextHandler.SESSIONS);
        dtgovWar.setContextPath("/dtgov-war");
        dtgovWar.setWelcomeFiles(new String[] { "index.jsp" });
        dtgovWar.setResourceBase(environment.getModuleDir("dtgov-war").getCanonicalPath());
        // Enable CDI
        dtgovWar.addEventListener(new Listener());
        dtgovWar.addEventListener(new BeanManagerResourceBindingListener());

        // Servlets
        ServletHolder governance = new ServletHolder(GovernanceServlet.class);
        governance.setInitOrder(1);
        dtgovWar.addServlet(governance, "/xyz_governance");
        ServletHolder restEasy = new ServletHolder(HttpServletDispatcher.class);
        restEasy.setInitParameter("javax.ws.rs.Application", "org.overlord.sramp.governance.services.GovernanceApplication");
        dtgovWar.addServlet(restEasy, "/brms/*");
        ServletHolder process = new ServletHolder(ProcessServlet.class);
        dtgovWar.addServlet(process, "/process");
        ServletHolder task = new ServletHolder(TaskServlet.class);
        dtgovWar.addServlet(task, "/task");


        // JSP support
        ServletHolder jsp = new ServletHolder(JspServlet.class) {
            /**
             * @see org.eclipse.jetty.servlet.ServletHolder#initJspServlet()
             */
            @Override
            protected void initJspServlet() throws Exception {
                ContextHandler ch = ContextHandler.getContextHandler(getServletHandler().getServletContext());
                ch.setClassLoader(Thread.currentThread().getContextClassLoader());
                super.initJspServlet();
            }
        };
        jsp.setInitParameter("fork", "false");
        jsp.setInitParameter("keepgenerated", "false");
        jsp.setInitParameter("logVerbosityLevel", "DEBUG");
        dtgovWar.addServlet(jsp, "*.jsp");
        // File resources
        ServletHolder resources = new ServletHolder(new DefaultServlet());
        resources.setInitParameter("dirAllowed", "true");
        resources.setInitParameter("pathInfoOnly", "false");
        dtgovWar.addServlet(resources, "/");


        handlers.addHandler(dtgovWar);
    }

    /**
     * @see org.overlord.commons.dev.server.DevServer#postStart(org.overlord.commons.dev.server.DevServerEnvironment)
     */
    @Override
    protected void postStart(DevServerEnvironment environment) throws Exception {
        System.out.println("----------  DONE  ---------------");
        System.out.println("Now try:  \n  http://localhost:8080/dtgov-war/index.jsp");
        System.out.println("---------------------------------");
    }

    /**
     * Creates an in-memory datasource.
     * @throws SQLException
     */
    private static DataSource createInMemoryDatasource() throws SQLException {
        System.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        BasicDataSource ds = new BasicDataSource();
        ds.setDriverClassName(Driver.class.getName());
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setUrl("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
        Connection connection = ds.getConnection();
        connection.close();
        return ds;
    }

}
