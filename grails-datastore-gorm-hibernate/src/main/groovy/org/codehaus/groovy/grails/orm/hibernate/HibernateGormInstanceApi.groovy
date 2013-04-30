package org.codehaus.groovy.grails.orm.hibernate

import groovy.transform.CompileStatic

import org.codehaus.groovy.grails.commons.DomainClassArtefactHandler
import org.codehaus.groovy.grails.commons.GrailsDomainClass
import org.codehaus.groovy.grails.domain.GrailsDomainClassMappingContext
import org.codehaus.groovy.grails.orm.hibernate.cfg.GrailsHibernateUtil
import org.codehaus.groovy.grails.orm.hibernate.metaclass.MergePersistentMethod
import org.codehaus.groovy.grails.orm.hibernate.metaclass.SavePersistentMethod
import org.grails.datastore.gorm.GormInstanceApi
import org.hibernate.FlushMode
import org.hibernate.LockMode
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.engine.EntityEntry
import org.hibernate.engine.SessionImplementor
import org.hibernate.proxy.HibernateProxy
import org.springframework.dao.DataAccessException
import org.springframework.orm.hibernate3.HibernateCallback
import org.springframework.orm.hibernate3.HibernateTemplate

/**
 * The implementation of the GORM instance API contract for Hibernate.
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@CompileStatic
class HibernateGormInstanceApi<D> extends GormInstanceApi<D> {
    private static final EMPTY_ARRAY = [] as Object[]

    private SavePersistentMethod saveMethod
    private MergePersistentMethod mergeMethod
    private HibernateTemplate hibernateTemplate
    private SessionFactory sessionFactory
    private ClassLoader classLoader
    private boolean cacheQueriesByDefault = false

    Map config = Collections.emptyMap()

    HibernateGormInstanceApi(Class<D> persistentClass, HibernateDatastore datastore, ClassLoader classLoader) {
        super(persistentClass, datastore)

        this.classLoader = classLoader
        sessionFactory = datastore.getSessionFactory()

        def mappingContext = datastore.mappingContext
        if (mappingContext instanceof GrailsDomainClassMappingContext) {
            GrailsDomainClassMappingContext domainClassMappingContext = (GrailsDomainClassMappingContext)mappingContext
            def grailsApplication = domainClassMappingContext.getGrailsApplication()
            GrailsDomainClass domainClass = (GrailsDomainClass)grailsApplication.getArtefact(DomainClassArtefactHandler.TYPE, persistentClass.name)
            config = (Map)grailsApplication.getFlatConfig().get('grails.gorm')
            saveMethod = new SavePersistentMethod(sessionFactory, classLoader, grailsApplication, domainClass, datastore)
            mergeMethod = new MergePersistentMethod(sessionFactory, classLoader, grailsApplication, domainClass, datastore)
            hibernateTemplate = new GrailsHibernateTemplate(sessionFactory, grailsApplication)
            cacheQueriesByDefault = GrailsHibernateUtil.isCacheQueriesByDefault(grailsApplication)
        } else {
            hibernateTemplate = new GrailsHibernateTemplate(sessionFactory)
        }
    }

    /**
     * Checks whether a field is dirty
     *
     * @param instance The instance
     * @param fieldName The name of the field
     *
     * @return true if the field is dirty
     */
    boolean isDirty(D instance, String fieldName) {
        SessionImplementor session = (SessionImplementor)sessionFactory.currentSession
        def entry = findEntityEntry(instance, session)
        if (!entry || !entry.loadedState) {
            return false
        }

        Object[] values = entry.persister.getPropertyValues(instance, session.entityMode)
        int[] dirtyProperties = entry.persister.findDirty(values, entry.loadedState, instance, session)
        int fieldIndex = entry.persister.propertyNames.findIndexOf { fieldName == it }
        return fieldIndex in dirtyProperties
    }

    /**
     * Checks whether an entity is dirty
     *
     * @param instance The instance
     * @return true if it is dirty
     */
    boolean isDirty(D instance) {
        SessionImplementor session = (SessionImplementor)sessionFactory.currentSession
        def entry = findEntityEntry(instance, session)
        if (!entry || !entry.loadedState) {
            return false
        }

        Object[] values = entry.persister.getPropertyValues(instance, session.entityMode)
        def dirtyProperties = entry.persister.findDirty(values, entry.loadedState, instance, session)
        return dirtyProperties != null
    }

    /**
     * Obtains a list of property names that are dirty
     *
     * @param instance The instance
     * @return A list of property names that are dirty
     */
    List getDirtyPropertyNames(D instance) {
        SessionImplementor session = (SessionImplementor)sessionFactory.currentSession
        def entry = findEntityEntry(instance, session)
        if (!entry || !entry.loadedState) {
            return []
        }

        Object[] values = entry.persister.getPropertyValues(instance, session.entityMode)
        int[] dirtyProperties = entry.persister.findDirty(values, entry.loadedState, instance, session)
        def names = []
        for (index in dirtyProperties) {
            names << entry.persister.propertyNames[index]
        }
        names
    }

    /**
     * Gets the original persisted value of a field.
     *
     * @param fieldName The field name
     * @return The original persisted value
     */
    D getPersistentValue(D instance, String fieldName) {
        SessionImplementor session = (SessionImplementor)sessionFactory.currentSession
        def entry = findEntityEntry(instance, session, false)
        if (!entry || !entry.loadedState) {
            return null
        }

        int fieldIndex = entry.persister.propertyNames.findIndexOf { fieldName == it }
        return fieldIndex == -1 ? null : entry.loadedState[fieldIndex]
    }

    @Override
    D lock(D instance) {
        hibernateTemplate.lock(instance, LockMode.UPGRADE)
    }

    @Override
    D refresh(D instance) {
        hibernateTemplate.refresh(instance)
        return instance
    }

    @Override
    D save(D instance) {
        if (saveMethod) {
            return saveMethod.invoke(instance, "save", EMPTY_ARRAY)
        }
        return super.save(instance)
    }

    D save(D instance, boolean validate) {
        if (saveMethod) {
            return saveMethod.invoke(instance, "save", [validate] as Object[])
        }
        return super.save(instance, validate)
    }

    @Override
    D merge(D instance) {
        if (mergeMethod) {
            mergeMethod.invoke(instance, "merge", EMPTY_ARRAY)
        }
        else {
            return super.merge(instance)
        }
    }

    @Override
    D merge(D instance, Map params) {
        if (mergeMethod) {
            mergeMethod.invoke(instance, "merge", [params] as Object[])
        }
        else {
            return super.merge(instance, params)
        }
    }

    @Override
    D save(D instance, Map params) {
        if (saveMethod) {
            return saveMethod.invoke(instance, "save", [params] as Object[])
        }
        return super.save(instance, params)
    }

    @Override
    D attach(D instance) {
        hibernateTemplate.lock(instance, LockMode.NONE)
        return instance
    }

    @Override
    void discard(D instance) {
        hibernateTemplate.evict instance
    }

    @Override
    void delete(D instance) {
        def obj = instance
        try {
            hibernateTemplate.execute({Session session ->
                session.delete obj
                if (shouldFlush()) {
                    session.flush()
                }
            } as HibernateCallback)
        }
        catch (DataAccessException e) {
            handleDataAccessException(hibernateTemplate, e)
        }
    }

    @Override
    void delete(D instance, Map params) {
        def obj = instance
        hibernateTemplate.delete obj
        if (shouldFlush(params)) {
            try {
                hibernateTemplate.flush()
            }
            catch (DataAccessException e) {
                handleDataAccessException(hibernateTemplate, e)
            }
        }
    }

    @Override
    boolean instanceOf(D instance, Class cls) {
        if (instance instanceof HibernateProxy) {
            return GrailsHibernateUtil.unwrapProxy(instance) in cls
        }
        return instance in cls
    }

    @Override
    boolean isAttached(D instance) {
        hibernateTemplate.contains instance
    }

    private EntityEntry findEntityEntry(D instance, SessionImplementor session, boolean forDirtyCheck = true) {
        def entry = session.persistenceContext.getEntry(instance)
        if (!entry) {
            return null
        }

        if (forDirtyCheck && !entry.requiresDirtyCheck(instance) && entry.loadedState) {
            return null
        }

        entry
    }
    /**
    * Session should no longer be flushed after a data access exception occurs (such a constriant violation)
    */
   private void handleDataAccessException(HibernateTemplate template, DataAccessException e) {
       try {
           template.execute({Session session ->
               session.setFlushMode(FlushMode.MANUAL)
           } as HibernateCallback)
       }
       finally {
           throw e
       }
   }

   private boolean shouldFlush(Map map = [:]) {
       if (map?.containsKey('flush')) {
           return Boolean.TRUE == map.flush
       }
       return config.autoFlush instanceof Boolean ? config.autoFlush : false
   }
}
