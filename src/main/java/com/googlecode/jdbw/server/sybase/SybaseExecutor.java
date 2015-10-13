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
package com.googlecode.jdbw.server.sybase;

import com.googlecode.jdbw.ResultSetInformation;
import com.googlecode.jdbw.impl.SQLExecutorImpl;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 *
 * @author Martin Berglund
 */
class SybaseExecutor extends SQLExecutorImpl {
    
    SybaseExecutor(Connection connection) {
        super(connection);
    }

    @Override
    protected ResultSetInformation newResultSetInformation(ResultSetMetaData resultSetMetaData, int resultSetCounter) throws SQLException {
        return new SybaseResultSetInformation(resultSetMetaData, resultSetCounter);
    }
    
    @Override
    protected PreparedStatement prepareInsertStatement(String SQL) throws SQLException {
        return connection.prepareStatement(SQL);
    }    

    @Override
    protected PreparedStatement prepareBatchUpdateStatement(String SQL) throws SQLException {
        return connection.prepareStatement(SQL);
    }
}
