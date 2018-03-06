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

package org.keycloak.connections.jpa;

import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakTransaction;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class JpaKeycloakTransaction implements KeycloakTransaction {

    private static final Logger logger = Logger.getLogger(JpaKeycloakTransaction.class);

    protected EntityManager em;

    public JpaKeycloakTransaction(EntityManager em) {
        this.em = em;
    }

    @Override
    public void begin() {
        logger.error("BEGIN");
        em.getTransaction().begin();
    }

    @Override
    public void commit() {
        try {
            logger.error("COMMIT");
            logger.trace("Committing transaction");
        //    releaseSavePoint("cockroach_restart");
            em.getTransaction().commit();
        } catch (PersistenceException e) {
            if (e.getCause().getMessage().contains("Retry")){
                throw new RuntimeException("RetryableExp", e);
            }

            throw PersistenceExceptionConverter.convert(e.getCause() != null ? e.getCause() : e);
        }
    }

    @Override
    public void rollback() {
        logger.error("ROLLBACK");
        logger.trace("Rollback transaction");
        em.getTransaction().rollback();
    }

    @Override
    public void setRollbackOnly() {
        em.getTransaction().setRollbackOnly();
    }

    @Override
    public boolean getRollbackOnly() {
        return  em.getTransaction().getRollbackOnly();
    }

    @Override
    public boolean isActive() {
        return em.getTransaction().isActive();
    }

    @Override
    public void createSavePoint(String savePointId){
        //TODO Fix SQL concat is Bad for security
        em.createNativeQuery("SAVEPOINT "+savePointId+";").executeUpdate();
    }

    @Override
    public void releaseSavePoint(String savePointId){
        System.out.println("Release savepoint");
        //TODO Fix SQL concat is Bad for security
        em.createNativeQuery("RELEASE SAVEPOINT " + savePointId + ";").executeUpdate();
    }

    @Override
    public void rollbackToSavePoint(String savePointId){
        //TODO Fix SQL concat is Bad for security
        em.createNativeQuery("ROLLBACK TO SAVEPOINT " + savePointId + ";").executeUpdate();

    }
}
