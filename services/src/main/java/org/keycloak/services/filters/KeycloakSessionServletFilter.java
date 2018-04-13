/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.services.filters;

import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.keycloak.common.ClientConnection;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.services.util.BufferedRequestWrapper;
import org.keycloak.services.util.ResponseErrorWrapper;

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class KeycloakSessionServletFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    /*@Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        servletRequest.setCharacterEncoding("UTF-8");

        final HttpServletRequest request = (HttpServletRequest)servletRequest;

        KeycloakSessionFactory sessionFactory = (KeycloakSessionFactory) servletRequest.getServletContext().getAttribute(KeycloakSessionFactory.class.getName());
        KeycloakSession session = sessionFactory.create();
        ResteasyProviderFactory.pushContext(KeycloakSession.class, session);
        ClientConnection connection = new ClientConnection() {
            @Override
            public String getRemoteAddr() {
                return request.getRemoteAddr();
            }

            @Override
            public String getRemoteHost() {
                return request.getRemoteHost();
            }

            @Override
            public int getRemotePort() {
                return request.getRemotePort();
            }

            @Override
            public String getLocalAddr() {
                return request.getLocalAddr();
            }

            @Override
            public int getLocalPort() {
                return request.getLocalPort();
            }
        };
        session.getContext().setConnection(connection);
        ResteasyProviderFactory.pushContext(ClientConnection.class, connection);

        KeycloakTransaction tx = session.getTransactionManager();
        ResteasyProviderFactory.pushContext(KeycloakTransaction.class, tx);
        tx.begin();

        try {
            filterChain.doFilter(servletRequest, servletResponse);
        } finally {
            if (servletRequest.isAsyncStarted()) {
                servletRequest.getAsyncContext().addListener(createAsyncLifeCycleListener(session));
            } else {
                closeSession(session);
            }
        }
    }*/


    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        servletRequest.setCharacterEncoding("UTF-8");

        // WARNING: This line is really important !!!
        // By a side effect, the query parameters disappears if not called... To investigate.
        servletRequest.getParameterNames();

        final HttpServletRequest requestBuffered = new BufferedRequestWrapper((HttpServletRequest) servletRequest);
        final HttpServletResponse responseBuffered = new ResponseErrorWrapper((HttpServletResponse) servletResponse);

        KeycloakSessionFactory sessionFactory = (KeycloakSessionFactory) requestBuffered.getServletContext().getAttribute(KeycloakSessionFactory.class.getName());
        KeycloakSession session = sessionFactory.create();
        ResteasyProviderFactory.pushContext(KeycloakSession.class, session);
        ClientConnection connection = new ClientConnection() {
            @Override
            public String getRemoteAddr() {
                return requestBuffered.getRemoteAddr();
            }

            @Override
            public String getRemoteHost() {
                return requestBuffered.getRemoteHost();
            }

            @Override
            public int getRemotePort() {
                return requestBuffered.getRemotePort();
            }

            @Override
            public String getLocalAddr() {
                return requestBuffered.getLocalAddr();
            }

            @Override
            public int getLocalPort() {
                return requestBuffered.getLocalPort();
            }
        };
        session.getContext().setConnection(connection);
        ResteasyProviderFactory.pushContext(ClientConnection.class, connection);

        //--- BEGIN CHANGES ---
       // final HttpServletRequest requestBuffered = new BufferedRequestWrapper((HttpServletRequest) request, 1024*256);
        //final HttpServletResponse responseBuffered = new ResponseErrorWrapper((HttpServletResponse) servletResponse);

        int attempts = 0;
        int maxRetries = 10;

        try {
            Class c = session.getProviderClass("org.keycloak.connections.jpa.JpaConnectionProvider");
            session.getProvider(c);
            KeycloakTransaction tx = session.getTransactionManager();
            ResteasyProviderFactory.pushContext(KeycloakTransaction.class, tx);

            //1. begin
            tx.begin();


            while (attempts < maxRetries) {
                ResteasyProviderFactory.pushContext(KeycloakSession.class, session);
                ResteasyProviderFactory.pushContext(ClientConnection.class, connection);
                ResteasyProviderFactory.pushContext(KeycloakTransaction.class, tx);
                System.out.println("TRIAL" + attempts);

                try {
                    //3. execute statements
                     filterChain.doFilter(requestBuffered, responseBuffered);
                    //filterChain.doFilter(request, servletResponse);
                    System.out.println("OK");
                    ((ResponseErrorWrapper) responseBuffered).flushError();
                    return;
                } catch (RuntimeException e) {
                    //4. retry transaction
                    // rollback
                    System.out.println("GOTCHA"+ attempts);
                    attempts++;
                    tx.rollback();


                    ((ResponseErrorWrapper) responseBuffered).clearError();
                    ((BufferedRequestWrapper) requestBuffered).retryRequest();
                    Thread.yield();
                }

            }

            //5. release savepoint
            //  try{
            //      System.out.println("Release SavePoint");
            //     tx.releaseSavePoint("cockroach_restart");
            //  }catch(Exception e){
            //      e.printStackTrace();
            //  }
        }catch(Exception e){
            e.printStackTrace();
        }finally{

            System.out.println("Finally");

            if (requestBuffered.isAsyncStarted()) {
                requestBuffered.getAsyncContext().addListener(createAsyncLifeCycleListener(session));
            } else {
                closeSession(session);
            }
        }

    }

    private AsyncListener createAsyncLifeCycleListener(final KeycloakSession session) {
        return new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                closeSession(session);
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                closeSession(session);
            }

            @Override
            public void onError(AsyncEvent event) {
                closeSession(session);
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
            }
        };
    }

    private void closeSession(KeycloakSession session) {
        // KeycloakTransactionCommitter is responsible for committing the transaction, but if an exception is thrown it's not invoked and transaction
        // should be rolled back
        if (session.getTransactionManager() != null && session.getTransactionManager().isActive()) {
            session.getTransactionManager().rollback();
        }

        session.close();
        ResteasyProviderFactory.clearContextData();
    }

    @Override
    public void destroy() {
    }
}
