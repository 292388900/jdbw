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
package com.googlecode.jdbw.jorm;

import com.googlecode.jdbw.DatabaseConnection;
import com.googlecode.jdbw.SQLDialect;
import com.googlecode.jdbw.metadata.Column;
import com.googlecode.jdbw.util.BatchUpdateHandlerAdapter;
import com.googlecode.jdbw.util.SQLWorker;
import com.googlecode.jdbw.util.SelfExecutor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

public class JORMDatabase {
    public static enum SearchPolicy {
        LOCAL_ONLY,
        CHECK_DATABASE_IF_MISSING,
        REFRESH_FIRST
    }
    
    private static class EntityMapping {
        Class<? extends JORMEntity> entityType;
        ClassTableMapping tableMapping;
        EntityInitializer entityInitializer;
        Class idType;
        Map<String, Column> columnMap;
    }
    
    private final DatabaseConnection databaseConnection;
    private final EntityCacheManager cacheManager;
    private final ClassTableMapping defaultClassTableMapping;
    private final EntityInitializer defaultEntityInitializer;
    private final Map<Class<? extends JORMEntity>, EntityMapping> entityMappings;

    public JORMDatabase(DatabaseConnection databaseConnection) {
        this(databaseConnection, new DefaultClassTableMapping(), new DefaultEntityInitializer());
    }
    
    public JORMDatabase(
            DatabaseConnection databaseConnection, 
            ClassTableMapping defaultClassTableMapping,
            EntityInitializer defaultEntityInitializer) {
        
        if(databaseConnection == null)
            throw new IllegalArgumentException("Cannot create JORMDatabase with null databaseConnection");
        if(defaultClassTableMapping == null)
            throw new IllegalArgumentException("Cannot create JORMDatabase with null defaultClassTableMapping");
        if(defaultEntityInitializer == null)
            throw new IllegalArgumentException("Cannot create JORMDatabase with null defaultEntityInitializer");
        
        this.databaseConnection = databaseConnection;
        this.cacheManager = new EntityCacheManager();
        this.entityMappings = new HashMap<Class<? extends JORMEntity>, EntityMapping>();
        this.defaultClassTableMapping = defaultClassTableMapping;
        this.defaultEntityInitializer = defaultEntityInitializer;
    }
    
    public <U, T extends JORMEntity<U>> void register(Class<T> entityType) throws SQLException {
        register(entityType, null);
    }
    
    public <U, T extends JORMEntity<U>> void register(Class<T> entityType, ClassTableMapping classTableMapping) throws SQLException {
        register(entityType, classTableMapping, null);
    }
    
    public <U, T extends JORMEntity<U>> void register(Class<T> entityType, ClassTableMapping classTableMapping, EntityInitializer initializer) throws SQLException {
        if(entityType == null)
            throw new IllegalArgumentException("Illegal call to JORMDatabase.register(...) with null entityType");
        
        if(classTableMapping == null)
            classTableMapping = defaultClassTableMapping;
        if(initializer == null)
            initializer = defaultEntityInitializer;
        
        synchronized(entityMappings) {
            if(entityMappings.containsKey(entityType)) {
                throw new IllegalArgumentException("Can't register " + entityType.getName() + 
                        " because it's already registered");
            }
            
            Type idType = getEntityIdType(entityType);
            if(idType == null) {
                throw new IllegalArgumentException("Could not determine the id type for " + entityType.getSimpleName());
            }
            else if(idType instanceof Class == false) {
                throw new IllegalArgumentException("Illegal id type for " + entityType.getSimpleName() + " (" + idType.toString() + " isn't a class)");
            }
            
            EntityMapping entityMapping = new EntityMapping();
            entityMapping.entityType = entityType;
            entityMapping.tableMapping = classTableMapping;
            entityMapping.entityInitializer = initializer;
            entityMapping.idType = (Class)idType;
            entityMapping.columnMap = 
                databaseConnection
                    .getCatalog(databaseConnection.getDefaultCatalogName())
                    .getSchema(databaseConnection.getServerType().getSQLDialect().getDefaultSchemaName())
                    .getTable(classTableMapping.getTableName(entityType))
                    .getColumnMap();
            
            entityMappings.put(entityType, entityMapping);
            cacheManager.createDataCache(entityType);
        }
    }
    
    public <U, T extends JORMEntity<U>> ArrayList<T> getAll(Class<T> type) {
        return new ArrayList<T>(cacheManager.getCache(type).allValues());
    }
    
    public <U, T extends JORMEntity<U>> T get(Class<T> type, U key) {
        return get(type, key, SearchPolicy.CHECK_DATABASE_IF_MISSING);
    }
    
    public <U, T extends JORMEntity<U>> T get(Class<T> type, U key, SearchPolicy searchPolicy) {
        if(searchPolicy == SearchPolicy.REFRESH_FIRST) {
            refresh(type, key);
        }
        T entity = cacheManager.getCache(type).get(key);
        if(entity == null && searchPolicy == SearchPolicy.CHECK_DATABASE_IF_MISSING) {
            return get(type, key, SearchPolicy.REFRESH_FIRST);
        }
        return entity;
    }
    
    public <U, T extends JORMEntity<U>> T newEntity(Class<T> type) throws SQLException {
        return newEntity(type, (U)null);
    }
    
    public <U, T extends JORMEntity<U>> T newEntity(Class<T> type, U id) throws SQLException {
        return newEntities(type, Arrays.asList(id)).get(0);
    }
    
    public <U, T extends JORMEntity<U>> List<T> newEntities(final Class<T> type, int numberOfEntities) throws SQLException {
        if(numberOfEntities < 0) {
            throw new IllegalArgumentException("Cannot call JORMDatabase.newEntities with < 0 entities to create");
        }
        if(numberOfEntities == 0) {
            return Collections.emptyList();
        }
        List<U> nulls = new ArrayList<U>();
        for(int i = 0; i < numberOfEntities; i++) {
            nulls.add(null);
        }
        return newEntities(type, nulls);
    }
    
    public <U, T extends JORMEntity<U>> List<T> newEntities(final Class<T> type, U... ids) throws SQLException {
        return newEntities(type, Arrays.asList(ids));
    }
    
    private <U, T extends JORMEntity<U>> List<T> newEntities(final Class<T> type, List<U> ids) throws SQLException {
        if(ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Error creating newEntity of type " + type.getSimpleName() + 
                    "; id parameter was empty");
        }
        
        SQLDialect sqlDialect = databaseConnection.getServerType().getSQLDialect();
        ClassTableMapping tableMapping = getClassTableMapping(type);
        List<String> fieldNames = tableMapping.getFieldNames(type);
        Object[] entityInitData = getEntityInitializationData(type);
        Object[] notNullEntityInitData = formatAsInputParameters(type, entityInitData, false /* don't include nulls */);
        
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ");
        sb.append(sqlDialect.escapeIdentifier(getTableName(type)));
        sb.append(" (");
        sb.append(sqlDialect.escapeIdentifier("id"));
        for(int i = 0; i < fieldNames.size(); i++) {
            //Only add non-null parameters
            if(entityInitData[i] != null) {
                String fieldName = fieldNames.get(i);
                sb.append(", ");
                sb.append(sqlDialect.escapeIdentifier(tableMapping.toColumnName(type, fieldName)));
            }
        }            
        sb.append(") VALUES(?");
        for(int i = 0; i < notNullEntityInitData.length; i++) {
            sb.append(", ?");
        }
        sb.append(")");
                
        List<Object[]> parameterValuesToInsert = new ArrayList<Object[]>();
        for(U id: ids) {
            if(id == null) {
               continue; 
            }
            if(id != null && !getIdType(type).isAssignableFrom(id.getClass())) {
                throw new IllegalArgumentException("Error creating newEntity of type " + type.getSimpleName() + 
                        "; expected id type " + getIdType(type) + " but got a " + id.getClass());
            }
            Object[] values = new Object[1 + notNullEntityInitData.length];
            values[0] = sqlDialect.safeType(getEntityColumnMap(type).get("id"), id);
            System.arraycopy(notNullEntityInitData, 0, values, 1, notNullEntityInitData.length);
            parameterValuesToInsert.add(values);
        }
        if(parameterValuesToInsert.size() > 0) {
            databaseConnection.createAutoExecutor().batchWrite(sb.toString(), parameterValuesToInsert);
        }
         
        final List<U> keyToCreateEntitiesFrom = new ArrayList<U>();
        for(U id: ids) {
            if(id != null) {
                keyToCreateEntitiesFrom.add((U)EntityProxy.convertToReturnType(getIdType(type), id));
            }
            else {
                Object[] parameters = new Object[1 + notNullEntityInitData.length];
                parameters[0] = null;
                System.arraycopy(notNullEntityInitData, 0, parameters, 1, notNullEntityInitData.length);
                U newId = (U)new SQLWorker(databaseConnection.createAutoExecutor()).insert(sb.toString(), parameters);
                newId = (U)normalizeGeneratedId(getIdType(type), newId);
                if(newId != null) {
                    keyToCreateEntitiesFrom.add(newId);
                }
            }
        }
        if(keyToCreateEntitiesFrom.size() < ids.size()) {
            throw new IllegalStateException("After inserting row into " + getTableName(type) + ", couldn't "
                    + "figure out what primary key was assigned, your JDBC driver or database server "
                    + "probably don't support the feature to return auto-generated keys or the column "
                    + "isn't set up to auto generate keys");
        }
        
        List<T> newEntities = new ArrayList<T>();
        for(U id: keyToCreateEntitiesFrom) {
            if(id == null)
                continue;
            
            if(!getIdType(type).isAssignableFrom(id.getClass())) {
                throw new IllegalArgumentException("Error creating newEntity of type " + type.getSimpleName() + 
                        "; expected id type " + getIdType(type).getName() + " but supplied (or auto-generated) id type was a " + id.getClass().getName());
            }
            T entity = newEntityProxy(type, id, entityInitData);
            cacheManager.getCache(type).put(entity);
            newEntities.add(entity);
        }
        return newEntities;
    }
    
    public <U, T extends JORMEntity<U>> T persist(T entity) throws SQLException {
        persist(Arrays.asList(entity));
        return entity;
    }
    
    public <U, T extends JORMEntity<U>> void persist(T... entities) throws SQLException {
        persist(Arrays.asList(entities));
    }
    
    public <U, T extends JORMEntity<U>> void persist(Collection<T> entities) throws SQLException {
        if(entities == null || entities.isEmpty()) {
            return;
        }
        
        SQLDialect sqlDialect = databaseConnection.getServerType().getSQLDialect();
        EntityProxy.Resolver<U, T> asResolver = (EntityProxy.Resolver<U, T>)entities.iterator().next();
        EntityProxy<U, T> proxy = asResolver.__underlying_proxy();
        Class<T> entityType = proxy.getEntityType();
        ClassTableMapping tableMapping = getClassTableMapping(entityType);
        
        List<String> fieldNames = tableMapping.getFieldNames(entityType);
        if(fieldNames.isEmpty())
            return;
        
        StringBuilder sb = new StringBuilder();
        sb.append("UPDATE ");
        sb.append(sqlDialect.escapeIdentifier(getTableName(entityType)));
        sb.append(" SET ");
        for(int i = 0; i < fieldNames.size(); i++) {
            if(i > 0) {
                sb.append(", ");
            }
            sb.append(sqlDialect.escapeIdentifier(tableMapping.toColumnName(entityType, fieldNames.get(i))));
            sb.append(" = ?");
        }
        sb.append(" WHERE ");
        sb.append(sqlDialect.escapeIdentifier("id"));
        sb.append(" = ?");
        
        List<Object[]> batches = new ArrayList<Object[]>();
        for(T entity: entities) {
            if(entity == null)
                continue;
            
            Object []values = new Object[fieldNames.size() + 1];
            for(int i = 0; i < fieldNames.size(); i++)
                values[i] = proxy.getValue(fieldNames.get(i));
            System.arraycopy(formatAsInputParameters(entityType, values, true), 0, values, 0, fieldNames.size());            
            values[fieldNames.size()] = sqlDialect.safeType(getEntityColumnMap(entityType).get("id"), entity.getId());
            batches.add(values);
        }
        if(batches.isEmpty()) {
            return;
        }
        databaseConnection.createAutoExecutor().batchWrite(new BatchUpdateHandlerAdapter(), sb.toString(), batches);
    }
    
    public <U, T extends JORMEntity<U>> void remove(T... entities) throws SQLException {
        remove(Arrays.asList(entities));
    }
    
    public <U, T extends JORMEntity<U>> void remove(Collection<T> entities) throws SQLException {
        entities = removeNullElementsFromCollection(entities);
        if(entities == null || entities.isEmpty()) {
            return;
        }
        
        EntityProxy.Resolver<U, T> asResolver = (EntityProxy.Resolver<U, T>)entities.iterator().next();
        EntityProxy<U, T> proxy = asResolver.__underlying_proxy();
        Class<T> entityType = proxy.getEntityType();
        List<U> keysToRemove = new ArrayList<U>(entities.size());
        for(T entity: entities) {
            keysToRemove.add(entity.getId());
        }
        remove(entityType, keysToRemove);
    }
    
    public <U, T extends JORMEntity<U>> void remove(Class<T> entityType, U... ids) throws SQLException {
        remove(entityType, Arrays.asList(ids));
    }
    
    public <U, T extends JORMEntity<U>> void remove(Class<T> entityType, Collection<U> ids) throws SQLException {
        if(entityType == null) {
            throw new IllegalArgumentException("Cannot call remove(...) with null entityType");
        }
        ids = removeNullElementsFromCollection(ids);    //Transforms the collection to a list, but we'll keep the name
        if(ids == null || ids.isEmpty()) {
            return;
        }
        
        SQLDialect sqlDialect = databaseConnection.getServerType().getSQLDialect();
        StringBuilder sb = new StringBuilder();
        sb.append("DELETE FROM ");        
        sb.append(sqlDialect.escapeIdentifier(getTableName(entityType)));
        sb.append(" WHERE ");
        sb.append(sqlDialect.escapeIdentifier("id"));
        sb.append(" IN (");
        for(int i = 0; i < ids.size(); i++) {
            if(i > 0)
                sb.append(", ");
            U id = ((List<U>)ids).get(i);
            sb.append(sqlDialect.formatValue(id, getEntityIdColumn(entityType).getSqlType()));
        }        
        sb.append(")");
        new SQLWorker(databaseConnection.createAutoExecutor()).write(sb.toString());
        cacheManager.getCache(entityType).removeAll(ids);
    }
        
    public void refresh() {
        refresh(new SelfExecutor());
    }
    
    public void refresh(Executor executor) {
        for(final Class entityType: (List<Class>)cacheManager.getAllKnownEntityTypes()) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    refresh(entityType);
                }
            });
        }
    }
    
    public <U, T extends JORMEntity<U>> void refresh(T... entities) {
        if(entities.length == 0)
            return;
        
        List<U> keys = new ArrayList<U>();
        for(T entity: entities) {
            keys.add(entity.getId());
        }
        refresh(entities[0].getClass(), keys);
    }
    
    public <U, T extends JORMEntity<U>> void refresh(Class<T> entityType) {
        SQLDialect sqlDialect = databaseConnection.getServerType().getSQLDialect();
        String sql = "SELECT " +
                        sqlDialect.escapeIdentifier("id") + 
                        getNonIdColumnsForSelect(entityType) +
                        " FROM " + 
                        sqlDialect.escapeIdentifier(getTableName(entityType));
        queryAndProcess(entityType, sql, null);
    }
    
    public <U, T extends JORMEntity<U>> void refresh(Class<T> entityType, U... keys) {
        refresh(entityType, Arrays.asList(keys));
    }
    
    private <U, T extends JORMEntity<U>> void refresh(Class<T> entityType, List<U> keys) {
        if(keys.isEmpty())
            return;
        
        SQLDialect sqlDialect = databaseConnection.getServerType().getSQLDialect();
        String sql = "SELECT " +
                        sqlDialect.escapeIdentifier("id") + 
                        getNonIdColumnsForSelect(entityType) +
                        " FROM " + 
                        sqlDialect.escapeIdentifier(getTableName(entityType)) +
                        " WHERE " +
                        sqlDialect.escapeIdentifier("id") +
                        " IN (";
        for(int i = 0; i < keys.size(); i++) {
            if(i > 0)
                sql += ", ";
            if(keys.get(i) == null)
                throw new IllegalArgumentException("Trying to refresh a " + entityType.getName() + " with key null");
            sql += sqlDialect.formatValue(keys.get(i), getEntityIdColumn(entityType).getSqlType());
        }        
        sql += ")";
        queryAndProcess(entityType, sql, keys);
    }
    
    private <U, T extends JORMEntity<U>> void queryAndProcess(Class<T> entityType, String sql, List<U> expectedKeys) {
        try {
            List<Object[]> rows = new SQLWorker(databaseConnection.createAutoExecutor()).query(sql);
            Set<U> idsReturned = new HashSet<U>();
            DataCache<U,T> entityDataMap = cacheManager.getCache(entityType);
            for(Object[] row: rows) {
                U id = (U)EntityProxy.convertToReturnType(getIdType(entityType), row[0]);
                idsReturned.add(id);
                T entity = null;
                if(!entityDataMap.contains(id)) {
                    entity = newEntityProxy(entityType, id, new Object[0]);
                    entityDataMap.put(entity);
                }
                else {
                    entity = entityDataMap.get(id);
                }
                
                if(entity instanceof EntityProxy.Resolver == false) {
                    throw new IllegalStateException("Encountered an entity which doesn't implement "
                            + "EntityProxy.Resolver!");
                }
                EntityProxy.Resolver<U, T> asResolver = (EntityProxy.Resolver<U, T>)entity;
                EntityProxy<U, T> proxy = asResolver.__underlying_proxy();
                proxy.populate(row);
            }
            
            //Remove missing rows
            Set<U> missingKeys = new HashSet<U>();
            if(expectedKeys == null)
                missingKeys.addAll(entityDataMap.allIds());
            else
                missingKeys.addAll(expectedKeys);
            missingKeys.removeAll(idsReturned);
            for(U key: missingKeys) {
                entityDataMap.remove(key);
            }
        }
        catch(SQLException e) {
            //TODO: CHANGE ME!!!
            e.printStackTrace();
        }
    }
    
    private <U, T extends JORMEntity<U>> T newEntityProxy(Class<T> entityType, U id, Object[] initializationData) {
        EntityProxy<U, T> proxy = new EntityProxy<U, T>(entityType, getClassTableMapping(entityType), id, initializationData);
        return (T)Proxy.newProxyInstance(
                    ClassLoader.getSystemClassLoader(), 
                    new Class[] { entityType, EntityProxy.Resolver.class }, 
                    proxy);
    }
    
    private <U, T extends JORMEntity<U>> String getNonIdColumnsForSelect(Class<T> entityType) {
        final ClassTableMapping tableMapping = getClassTableMapping(entityType);
        List<String> fieldNames = tableMapping.getFieldNames(entityType);
        StringBuilder sb = new StringBuilder();
        for(String fieldName: fieldNames) {
            sb.append(", ");
            sb.append(databaseConnection.getServerType().getSQLDialect().escapeIdentifier(
                    tableMapping.toColumnName(entityType, fieldName)));
        }
        return sb.toString();
    }
    
    private <U> U normalizeGeneratedId(Class<U> type, U generatedId) {
        if(type.isAssignableFrom(generatedId.getClass())) {
            return generatedId;
        }
        else if(type == Integer.class) {
            if(generatedId.getClass() == Long.class) {
                return (U)new Integer((int)((Long)generatedId).longValue());
            }
            else if(generatedId.getClass() == BigInteger.class) {
                return (U)new Integer(((BigInteger)generatedId).intValue());
            }
        }
        else if(type == Long.class) {
            if(generatedId.getClass() == Integer.class) {
                return (U)new Long(((Integer)generatedId).longValue());
            }
            else if(generatedId.getClass() == BigInteger.class) {
                return (U)new Long(((BigInteger)generatedId).longValue());
            }
        }
        return generatedId;
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

    private Type getEntityIdType(Class entityType) {
        //Try to determine the type of the id
        for(Type type: entityType.getGenericInterfaces()) {
            if(type instanceof ParameterizedType) {
                ParameterizedType ptype = (ParameterizedType)type;
                if(ptype.getRawType() == JORMEntity.class) {
                    return (Class)ptype.getActualTypeArguments()[0];
                }
            }
            else if(type instanceof Class) {
                Type idType = getEntityIdType((Class)type);
                if(idType != null)
                    return idType;
            }
        }
        return null;
    }
    
    private <U, T extends JORMEntity<U>> Object[] getEntityInitializationData(Class<T> entityClass) {
        List<String> entityFields = getEntityFields(entityClass);
        EntityInitializer initializer = getEntityInitializer(entityClass);
        Object[] result = new Object[entityFields.size()];
        int counter = 0;
        for(String fieldName: entityFields) {
            result[counter++] = initializer.getInitialValue(entityClass, fieldName);            
        }
        return result;
    }
        
    private <U, T extends JORMEntity<U>> String getTableName(Class<T> entityType) {
        return getClassTableMapping(entityType).getTableName(entityType);
    }
    
    private <U, T extends JORMEntity<U>> List<String> getEntityFields(Class<T> entityClass) {
        return getClassTableMapping(entityClass).getFieldNames(entityClass);
    }
    
    private <U, T extends JORMEntity<U>> EntityInitializer getEntityInitializer(Class<T> entityClass) {
        EntityMapping mapping = getMapping(entityClass);
        if(mapping.entityInitializer == null)
            return defaultEntityInitializer;
        else
            return mapping.entityInitializer;
    }
    
    private <U, T extends JORMEntity<U>> ClassTableMapping getClassTableMapping(Class<T> entityClass) {
        EntityMapping mapping = getMapping(entityClass);
        if(mapping.tableMapping == null)
            return defaultClassTableMapping;
        else
            return mapping.tableMapping;
    }
    
    private <U, T extends JORMEntity<U>> Map<String, Column> getEntityColumnMap(Class<T> entityClass) {
        return getMapping(entityClass).columnMap;
    }
    
    private <U, T extends JORMEntity<U>> Column getEntityIdColumn(Class<T> entityClass) {
        return getEntityColumnMap(entityClass).get("id");
    }
    
    private <U, T extends JORMEntity<U>> Class getIdType(Class<T> entityType) {
        return getMapping(entityType).idType;
    }
    
    private <U, T extends JORMEntity<U>> EntityMapping getMapping(Class<T> entityType) {
        synchronized(entityMappings) {
            if(!entityMappings.containsKey(entityType))
                throw new IllegalArgumentException("Trying to access the table name of an unregistered entity type " + entityType.getName());
            return entityMappings.get(entityType);
        }
    }
    
    private <U, T extends JORMEntity<U>> Object[] formatAsInputParameters(Class<T> entityType, Object[]data, boolean includeNull) {
        List list = new ArrayList();
        ClassTableMapping classTableMapping = getClassTableMapping(entityType);
        List<String> fieldNames = classTableMapping.getFieldNames(entityType);
        Map<String, Column> columnMap = getEntityColumnMap(entityType);
        for(int i = 0; i < fieldNames.size(); i++) {
            Object fieldData = data[i];
            if(fieldData == null && !includeNull) {
                continue;
            }
            
            Column column = columnMap.get(classTableMapping.toColumnName(entityType, fieldNames.get(i)));
            list.add(databaseConnection.getServerType().getSQLDialect().safeType(column, fieldData));
        }
        return list.toArray();
    }
}