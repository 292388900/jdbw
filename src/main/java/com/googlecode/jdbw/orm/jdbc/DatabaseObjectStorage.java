/*
 * This file is part of jdbw (http://code.google.com/p/jdbw/).
 * 
 * jdbw is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * Copyright (C) 2007-2012 Martin Berglund
 */
package com.googlecode.jdbw.orm.jdbc;

import com.googlecode.jdbw.DatabaseConnection;
import com.googlecode.jdbw.DatabaseTransaction;
import com.googlecode.jdbw.SQLExecutor;
import com.googlecode.jdbw.TransactionIsolation;
import com.googlecode.jdbw.orm.AbstractTriggeredExternalObjectStorage;
import com.googlecode.jdbw.orm.AutoIdAssignableObjectStorage;
import com.googlecode.jdbw.orm.Identifiable;
import com.googlecode.jdbw.orm.Modifiable;
import com.googlecode.jdbw.orm.ObjectStorageException;
import com.googlecode.jdbw.orm.Persistable;
import com.googlecode.jdbw.util.BatchUpdateHandlerAdapter;
import com.googlecode.jdbw.util.SQLWorker;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseObjectStorage extends AbstractTriggeredExternalObjectStorage implements AutoIdAssignableObjectStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseObjectStorage.class);

    private final DatabaseConnection databaseConnection;
    
    private final TableMappings tableMappings;
    private final DatabaseTableDataStorage databaseTableDataStorage;

    public DatabaseObjectStorage(DatabaseConnection databaseConnection) {
        this.databaseConnection = databaseConnection;
        this.databaseTableDataStorage = new DatabaseTableDataStorage();
        this.tableMappings = new TableMappings();
    }
    
    @Override
    public <U, T extends Identifiable<U>> void register(
            Class<T> objectType) {
        
        if(objectType == null) {
            throw new IllegalArgumentException("Cannot call register(...) with null objectType");
        }
        
        register(new DefaultTableMapping<U, T>(objectType));
    }
    
    public <U, T extends Identifiable<U>> void register(
            TableMapping<U, T> tableMapping) {
        
        if(tableMapping == null) {
            throw new IllegalArgumentException("Cannot register with a null table mapping");
        }
        if(isRegistered(tableMapping.getObjectType())) {
            return;
        }
        
        Class<U> idType = getIdentifiableIdType(tableMapping.getObjectType());
        if(idType == null) {
            throw new IllegalArgumentException("Cannot register " + tableMapping.getObjectType().getSimpleName() + 
                    " because the id type cannot be resolved");
        }
        
        databaseTableDataStorage.add(tableMapping, idType);
        tableMappings.add(tableMapping);
    }
    
    @Override
    public <U, T extends Identifiable<U>> T get(Class<T> type, U key, CachePolicy searchPolicy) {
        if(searchPolicy == CachePolicy.EXTERNAL_GET) {
            refresh(type, key);
        }
        return databaseTableDataStorage.get(type).getProxyObject(key);
    }

    @Override
    public <U, T extends Identifiable<U>> List<T> getAll(Class<T> type, CachePolicy searchPolicy) {
        if(searchPolicy == CachePolicy.EXTERNAL_GET) {
            refresh(type);
        }
        return databaseTableDataStorage.get(type).getAllProxyObjects();
    }

    @Override
    public void refresh() {
        List<Class<? extends Identifiable>> registeredObjectTypes = databaseTableDataStorage.getAllObjectTypes();
        for(final Class objectType: registeredObjectTypes) {
            try {
                refresh(objectType);
            }
            catch(Throwable e) {
                LOGGER.error("Error refreshing " + objectType.getSimpleName(), e);
            }
        }
    }

    @Override
    public <U, T extends Identifiable<U>> T refresh(T object) {
        Class<T> objectType = getObjectType(object);
        refresh(objectType, object.getId());
        return get(objectType, object.getId());
    }

    @Override
    public <U, T extends Identifiable<U>> void refresh(T... objects) {
        List<T> nonNullObjects = removeNullElementsFromCollection(Arrays.asList(objects));
        if(nonNullObjects.isEmpty())
            return;
        
        List<U> keys = new ArrayList<U>();
        for(T object: nonNullObjects) {
            keys.add(object.getId());
        }
        
        Class<T> objectType = getObjectType(nonNullObjects.get(0));
        refresh(objectType, keys);
    }

    @Override
    public <U, T extends Identifiable<U>> void refresh(Class<T> objectType) {
        if(!isRegistered(objectType)) {
            throw new IllegalArgumentException("Cannot refresh non-registered type " + objectType.getSimpleName());
        }
        
        String sql = tableMappings.get(objectType).getSelectAll(databaseConnection.getServerType().getSQLDialect());
        
        List<Object[]> rows;
        try {
             rows = new SQLWorker(databaseConnection.createAutoExecutor()).query(sql);
        }
        catch(SQLException e) {
            throw new ObjectStorageException("Database error when refreshing " + objectType.getSimpleName(), e);
        }
        databaseTableDataStorage.get(objectType).setRows(rows);
    }
    
    @Override
    public <U, T extends Identifiable<U>> void refresh(Class<T> objectType, U... keys) {
        refresh(objectType, Arrays.asList(keys));
    }
    
    private <U, T extends Identifiable<U>> void refresh(Class<T> objectType, List<U> keys) {
        if(!isRegistered(objectType)) {
            throw new IllegalArgumentException("Cannot refresh non-registered type " + objectType.getSimpleName());
        }
        
        String sql = tableMappings.get(objectType).getSelectSome(
                databaseConnection.getServerType().getSQLDialect(),
                keys);
        List<Object[]> rows;
        try {
            rows = new SQLWorker(databaseConnection.createAutoExecutor()).query(sql, keys.toArray());
        }
        catch(SQLException e) {
            throw new ObjectStorageException("Database error when refreshing " + keys.size() + " " + objectType.getSimpleName(), e);
        }
        databaseTableDataStorage.get(objectType).addOrUpdateRows(rows);
    }

    @Override
    public <U, T extends Object & Identifiable<U> & Modifiable> T newObject(Class<T> type) {
        return newObject(type, null);
    }
    
    @Override
    public <U, T extends Identifiable<U> & Modifiable> T newObject(Class<T> type, U id) {
        return newObjects(type, Arrays.asList(id)).get(0);
    }
    
    @Override
    public <U, T extends Identifiable<U> & Modifiable> List<T> newObjects(Class<T> type, int numberOfObjects) {
        if(numberOfObjects < 0) {
            throw new IllegalArgumentException("Cannot call DatabaseObjectStorage.newObjects(...) with < 0 objects to create");
        }
        if(numberOfObjects == 0) {
            return Collections.emptyList();
        }
        List<U> nulls = new ArrayList<U>();
        for(int i = 0; i < numberOfObjects; i++) {
            nulls.add(null);
        }
        return newObjects(type, nulls);
    }

    @Override
    public <U, T extends Identifiable<U> & Modifiable> List<T> newObjects(Class<T> type, U... ids) {
        return newObjects(type, Arrays.asList(ids));
    }
    
    @Override
    public <U, T extends Identifiable<U> & Modifiable> List<T> newObjects(final Class<T> objectType, Collection<U> ids) {
        if(ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        if(!isRegistered(objectType)) {
            throw new IllegalArgumentException("Cannot create new objects of unregistered type " + objectType.getSimpleName());
        }
        
        Map<String, Object> objectInitData = getObjectInitializationData(objectType);
        List<T> newObjects = new ArrayList<T>();
        for(U id: ids) {
            if(id != null && !getIdentifiableIdType(objectType).isAssignableFrom(id.getClass())) {
                throw new IllegalArgumentException("Error creating new object of type " + objectType.getSimpleName() + 
                        "; expected id type " + getIdentifiableIdType(objectType).getSimpleName() + 
                        " but supplied (or auto-generated) id type was a " + id.getClass().getName());
            }
            T entity = newInsertableObjectProxy(objectType, id, objectInitData);
            newObjects.add(entity);
        }
        return newObjects;
    }

    @Override
    public <U, T extends Identifiable<U> & Modifiable> List<T> persist(Collection<Persistable<U, T>> persistables) {
        if(persistables == null ||persistables.isEmpty()) {
            return Collections.emptyList();
        }
        Class<T> type = persistables.iterator().next().getObjectType();
        List<InsertableObjectProxyHandler.Finalized<U, T>> toInsert = new ArrayList<InsertableObjectProxyHandler.Finalized<U, T>>();
        List<UpdatableObjectProxyHandler.Finalized<U, T>> toUpdate = new ArrayList<UpdatableObjectProxyHandler.Finalized<U, T>>();        
        for(Persistable<U, T> persistable: persistables) {
            if(persistable instanceof InsertableObjectProxyHandler.Finalized) {
                toInsert.add((InsertableObjectProxyHandler.Finalized)persistable);
            }
            else if(persistable instanceof UpdatableObjectProxyHandler.Finalized) {
                toUpdate.add((UpdatableObjectProxyHandler.Finalized)persistable);
            }
        }
        List<T> result = new ArrayList<T>();
        List<U> keys = new ArrayList<U>();
        DatabaseTransaction transaction = null;
        try {
            transaction = databaseConnection.beginTransaction(TransactionIsolation.READ_UNCOMMITTED);
            keys.addAll(insert(transaction, toInsert));
            keys.addAll(update(transaction, toUpdate));
            transaction.commit();
        }
        catch(Exception e) {
            try {
                if(transaction != null) {
                    transaction.rollback();
                }
            }
            catch(SQLException e2) {}
            if(e instanceof SQLException)
                throw new ObjectStorageException("Database error when persisting " + persistables.size() + " objects", e);
            else if(e instanceof ObjectStorageException) {
                throw (ObjectStorageException)e;
            }
            else if(e instanceof RuntimeException)
                throw (RuntimeException)e;
            else
                throw new ObjectStorageException("Unknown error when persisting " + persistables.size() + " objects", e);
        }
        
        for(U key: keys) {
            result.add((T)get(type, key, CachePolicy.LOCAL_GET));
        }
        return result;
    }
    
    private <U, T extends Identifiable<U> & Modifiable> List<U> insert(
            SQLExecutor executor, 
            List<InsertableObjectProxyHandler.Finalized<U, T>> persistables) {
        
        List<InsertableObjectProxyHandler.Finalized<U, T>> insertionsWithAutoGeneratedIds 
                = new ArrayList<InsertableObjectProxyHandler.Finalized<U, T>>();
        List<InsertableObjectProxyHandler.Finalized<U, T>> insertionsWithoutAutoGeneratedIds 
                = new ArrayList<InsertableObjectProxyHandler.Finalized<U, T>>();
        
        for(InsertableObjectProxyHandler.Finalized<U, T> persistable: persistables) {
            if(persistable.getId() == null) {
                insertionsWithAutoGeneratedIds.add(persistable);
            }
            else {
                insertionsWithoutAutoGeneratedIds.add(persistable);
            }
        }
        
        List<U> keys = new ArrayList<U>();
        keys.addAll(insertAutoGeneratedIdRows(executor, insertionsWithAutoGeneratedIds));
        keys.addAll(insertNormalRows(executor, insertionsWithoutAutoGeneratedIds));
        return keys;
    }
    
    private <U, T extends Identifiable<U> & Modifiable> List<U> insertAutoGeneratedIdRows(
            SQLExecutor executor,
            List<InsertableObjectProxyHandler.Finalized<U, T>> persistables) {
        
        if(persistables == null || persistables.isEmpty()) {
            return Collections.emptyList();
        }
        Class<T> objectType = persistables.get(0).getObjectType();
        String sql = tableMappings.get(objectType).getInsert(databaseConnection.getServerType().getSQLDialect());
        List<U> keys = new ArrayList<U>();
        for(InsertableObjectProxyHandler.Finalized<U, T> persistable: persistables) {
            Object[] values = persistable.getValues();
            U newId = null;
            try {
                newId = (U)new SQLWorker(executor).insert(sql, values);
            }
            catch(SQLException e) {
                throw new ObjectStorageException("Database error when inserting " + objectType.getSimpleName(), e);
            }
            
            if(newId != null) {
                values[0] = newId;
                newId = databaseTableDataStorage.get(objectType).addOrUpdateRow(values);    //Id may be converted to the correct format
                keys.add(newId);
            }
        }
        return keys;
    }
    
    private <U, T extends Identifiable<U> & Modifiable> List<U> insertNormalRows(
            SQLExecutor executor,
            List<InsertableObjectProxyHandler.Finalized<U, T>> persistables) {
        
        if(persistables == null || persistables.isEmpty()) {
            return Collections.emptyList();
        }
        Class<T> objectType = persistables.get(0).getObjectType();
        String sql = tableMappings.get(objectType).getInsert(databaseConnection.getServerType().getSQLDialect());
        List<U> keys = new ArrayList<U>();
        List<Object[]> batchParameters = new ArrayList<Object[]>();
        for(InsertableObjectProxyHandler.Finalized<U, T> persistable: persistables) {
            batchParameters.add(persistable.getValues());
            keys.add(persistable.getId());
        }
        try {
            executor.batchWrite(new BatchUpdateHandlerAdapter(), sql, batchParameters);
        }
        catch(SQLException e) {
            throw new ObjectStorageException("Database error when inserting " + persistables.size() + " " + objectType.getSimpleName(), e);
        }
        databaseTableDataStorage.get(objectType).addOrUpdateRows(batchParameters);
        return keys;
    }
    
    private <U, T extends Identifiable<U> & Modifiable> List<U> update(
            SQLExecutor executor, 
            List<UpdatableObjectProxyHandler.Finalized<U, T>> persistables) {
        
        if(persistables == null || persistables.isEmpty()) {
            return Collections.emptyList();
        }
        Class<T> objectType = persistables.get(0).getObjectType();
        String sql = tableMappings.get(objectType).getUpdate(databaseConnection.getServerType().getSQLDialect());
        List<U> keys = new ArrayList<U>();
        List<Object[]> batchParameters = new ArrayList<Object[]>();
        for(UpdatableObjectProxyHandler.Finalized<U, T> persistable: persistables) {
            batchParameters.add(persistable.getValues());
            keys.add(persistable.getId());
        }
        try {
            executor.batchWrite(new BatchUpdateHandlerAdapter(), sql, batchParameters);
        }
        catch(SQLException e) {
            throw new ObjectStorageException("Database error when updating " + persistables.size() + " " + objectType.getSimpleName(), e);
        }
        databaseTableDataStorage.get(objectType).updateRows(batchParameters);
        return keys;
    }

    @Override
    public <U, T extends Identifiable<U>> void delete(Collection<T> objects) {
        objects = removeNullElementsFromCollection(objects);
        if(objects == null || objects.isEmpty()) {
            return;
        }
        
        Class<T> objectType = getObjectType(objects.iterator().next());
        List<U> keysToRemove = new ArrayList<U>(objects.size());
        for(T object: objects) {
            keysToRemove.add(object.getId());
        }
        delete(objectType, keysToRemove);
    }

    @Override
    public <U, T extends Identifiable<U>> void delete(Class<T> objectType, Collection<U> ids) {
        if(objectType == null) {
            throw new IllegalArgumentException("Cannot call delete(...) with null objectType");
        }
        ids = removeNullElementsFromCollection(ids);    //Transforms the collection to a list, but we'll keep the name
        if(ids == null || ids.isEmpty()) {
            return;
        }
        
        DatabaseTransaction transaction = null;
        try {
            transaction = databaseConnection.beginTransaction(TransactionIsolation.READ_UNCOMMITTED);
            String sql = tableMappings.get(objectType).getDelete(
                databaseConnection.getServerType().getSQLDialect(),
                ids.size());
            Object[] parameters = new Object[ids.size()];
            for(int i = 0; i < ids.size(); i++) {
                parameters[i] = ((List<U>)ids).get(i);
            }
            new SQLWorker(transaction).write(sql, parameters);
            transaction.commit();
        }
        catch(Exception e) {
            try {
                if(transaction != null) {
                    transaction.rollback();
                }
            }
            catch(SQLException e2) {}
            if(e instanceof SQLException)
                throw new ObjectStorageException("Database error when deleting " + ids.size() + " " + objectType.getSimpleName(), e);
            else if(e instanceof ObjectStorageException)
                throw (ObjectStorageException)e;
            else if(e instanceof RuntimeException)
                throw (RuntimeException)e;
            else
                throw new ObjectStorageException("Unknown error when deleting " + ids.size() + " " + objectType.getSimpleName(), e);
        }
        
        databaseTableDataStorage.get(objectType).remove((List<U>)ids);
    }
    
    public <U, T extends Identifiable<U>> boolean isRegistered(Class<T> objectType) {
        return tableMappings.get(objectType) != null;
    }

    private Class getIdentifiableIdType(Class<? extends Identifiable> objectType) {
        //Try to determine the type of the id
        for(Type type: objectType.getGenericInterfaces()) {
            if(type instanceof ParameterizedType) {
                ParameterizedType ptype = (ParameterizedType)type;
                if(ptype.getRawType() == Identifiable.class) {
                    return (Class)ptype.getActualTypeArguments()[0];
                }
            }
            else if(type instanceof Class) {
                Class idType = getIdentifiableIdType((Class)type);
                if(idType != null)
                    return idType;
            }
        }
        return null;
    }
    
    private <U, T extends Identifiable<U>> Class<T> getObjectType(T proxyObject) {
        InvocationHandler invocationHandler = Proxy.getInvocationHandler(proxyObject);
        if(invocationHandler instanceof CommonProxyHandler == false) {
            throw new IllegalArgumentException("Proxy object " + proxyObject + " had an unknown "
                    + "invocation handler (" + invocationHandler.getClass().getName() + ")");
        }
        return ((CommonProxyHandler)invocationHandler).getObjectType();
    }
    
    private <U, T extends Identifiable<U> & Modifiable> T newInsertableObjectProxy(
            Class<T> objectType,
            U key,
            Map<String, Object> initialValues) {
        
        InsertableObjectProxyHandler<U, T> handler = new InsertableObjectProxyHandler<U, T>(
                tableMappings.get(objectType), 
                objectType, 
                key,
                initialValues);
        return (T)Proxy.newProxyInstance(ClassLoader.getSystemClassLoader(), new Class[] {objectType}, handler);
    }
    
    private <U, T extends Identifiable<U>> Map<String, Object> getObjectInitializationData(Class<T> objectType) {
        TableMapping<U, T> tableMapping = tableMappings.get(objectType);
        Map<String, Object> initData = new HashMap<String, Object>();
        for(String fieldName: tableMapping.getFieldNames()) {
            initData.put(fieldName, null);
        }
        return initData;
    }
    
    private <T> List<T> removeNullElementsFromCollection(Collection<T> collection) {
        if(collection == null)
            return null;
        
        List<T> list = new ArrayList<T>(collection.size());
        for(T element: collection) {
            if(element != null) {
                list.add(element);
            }
        }
        return list;
    }
}
