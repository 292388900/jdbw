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
package com.googlecode.jdbw.impl;

import com.googlecode.jdbw.DataSourceCloser;
import com.googlecode.jdbw.DataSourceFactory;
import com.googlecode.jdbw.DatabaseConnection;
import com.googlecode.jdbw.DatabaseConnectionFactory;
import com.googlecode.jdbw.DatabaseServerType;

import java.util.Properties;
import javax.sql.DataSource;

/**
 * Common base class for DatabaseConnectionFactory:s which keeps a few shared properties and method implementations.
 * @author Martin Berglund
 */
public abstract class BasicDatabaseConnectionFactory implements DatabaseConnectionFactory {

    private final DatabaseServerType databaseServerType;
    private final String jdbcUrl;
    private final Properties connectionProperties;

    /**
     * Creates a new connection factory with a specified type and JDBC url
     * @param databaseServerType What type of database server this connection factory creates connections for
     * @param jdbcUrl The URL to use when creating connections to this database server
     */
    protected BasicDatabaseConnectionFactory(
            DatabaseServerType databaseServerType,
            String jdbcUrl) {
        this.databaseServerType = databaseServerType;
        this.jdbcUrl = jdbcUrl;
        this.connectionProperties = new Properties();
    }
    
    @Override
    public final DatabaseConnectionFactory setConnectionProperty(String propertyName, String value) {
        connectionProperties.setProperty(propertyName, value == null ? "" : value);
        return this;
    }

    @Override
    public DatabaseConnection connect(final DataSourceFactory dataSourceFactory) {
        return new DatabaseConnectionImpl(
                dataSourceFactory.newDataSource(jdbcUrl, connectionProperties),
                new DataSourceCloser() {
                    @Override
                    public void closeDataSource(DataSource dataSource) {
                        dataSourceFactory.close(dataSource);
                    }
                },
                databaseServerType);
    }
}
