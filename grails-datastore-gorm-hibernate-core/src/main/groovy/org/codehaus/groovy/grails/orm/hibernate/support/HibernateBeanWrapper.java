/*
 * Copyright 2013 the original author or authors.
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
package org.codehaus.groovy.grails.orm.hibernate.support;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;

import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;

/**
 * BeanWrapper implementaion that will not lazy initialize entity properties.
 */
public class HibernateBeanWrapper extends BeanWrapperImpl {

    private static final Logger log = LoggerFactory.getLogger(HibernateBeanWrapper.class);

    public HibernateBeanWrapper() {
        // default
    }

    public HibernateBeanWrapper(boolean b) {
        super(b);
    }

    public HibernateBeanWrapper(Object o) {
        super(o);
    }

    public HibernateBeanWrapper(Class<?> aClass) {
        super(aClass);
    }

    public HibernateBeanWrapper(Object o, String s, Object o1) {
        super(o, s, o1);
    }

    /**
     * Checks Hibernate.isInitialized before calling super method.
     *
     * @param name target property
     * @return null if false or super'name value if true
     * @throws BeansException
     */
    @Override
    public Object getPropertyValue(String name) throws BeansException {
        PropertyDescriptor desc = getPropertyDescriptor(name);
        Method method = desc.getReadMethod();
        Object owner = getWrappedInstance();
        try {
            if (Hibernate.isInitialized(method.invoke(owner))) {
                return super.getPropertyValue(name);
            }
        }
        catch (Exception e) {
            log.error("Error checking Hibernate initialization on method " +
                    method.getName() + " for class " + owner.getClass().getName(), e);
        }
        return null;
    }
}
