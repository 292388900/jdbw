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
 * Copyright (C) 2007-2012 mabe02
 */

package com.googlecode.jdbw.util;

import com.googlecode.jdbw.SQLExecutor;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * This utility class can be very helpful when sending simple queries to the
 * database and you don't want to get too involved in the details. It will help
 * you to send queries and get result back in simple and familiar formats, as 
 * well as grabbing only limited parts of the result set.
 * 
 * You normally create an SQLWorker on top of an auto-executor, but you can
 * also use a normal transaction.
 * @author mabe02
 */
public class SQLWorker
{
    private final SQLExecutor executor;

    /**
     * Creates a new SQLWorker with a specified underlying SQLExecutor to use
     * for the actual database communication.
     * @param executor SQLExecutor send the queries to
     */
    public SQLWorker(SQLExecutor executor)
    {
        this.executor = executor;
    }

    /**
     * Sends a query to the database and returns the whole ResultSet as a list of
     * Object arrays.
     * @param SQL SQL to send to the database server
     * @param parameters Parameters to substitute ?:s for in the SQL string
     * @return The entire result set, converted into a list of Object arrays, 
     * where each array in the list is one row in the result set
     * @throws SQLException If any database error occurred
     */
    public List<Object[]> query(String SQL, Object... parameters) throws SQLException
    {
        final List<Object[]> result = new ArrayList<Object[]>();
        executor.execute(new ExecuteResultHandlerAdapter() {
            @Override
            public boolean nextRow(Object[] row)
            {
                result.add(row);
                return true;
            }
        }, SQL, parameters);
        return result;
    }

    /**
     * Sends a query to the database and returns the whole ResultSet as a list of
     * String arrays.
     * @param SQL SQL to send to the database server
     * @param parameters Parameters to substitute ?:s for in the SQL string
     * @return The entire result set, converted into a list of String arrays, 
     * where each array in the list is one row in the result set and each element
     * in the String arrays are the .toString() call on the underlying result 
     * set object.
     * @throws SQLException If any database error occurred
     */
    public List<String[]> queryAsStrings(String string, Object... parameters) throws SQLException
    {
        List<String[]> result = new ArrayList<String[]>();
        for(Object[] row: query(string, parameters)) {
            String[] stringRow = new String[row.length];
            for(int i = 0; i < row.length; i++)
                if(row[i] == null)
                    stringRow[i] = null;
                else
                    stringRow[i] = row[i].toString();
            result.add(stringRow);
        }
        return result;
    }

    public void write(String SQL, Object... parameters) throws SQLException
    {
        executor.execute(new ExecuteResultHandlerAdapter(), SQL, parameters);
    }

    public Object insert(String SQL, Object... parameters) throws SQLException
    {
        final List<Object> autoGeneratedKeys = new ArrayList<Object>();
        executor.execute(new ExecuteResultHandlerAdapter() {
                @Override
                public void onGeneratedKey(Object object)
                {
                    autoGeneratedKeys.add(object);
                }
            }, SQL, parameters);
        if(autoGeneratedKeys.isEmpty())
            return null;
        else
            return autoGeneratedKeys.get(0);
    }

    public Object[] top(String SQL, Object... parameters) throws SQLException
    {
        final List<Object[]> result = new ArrayList<Object[]>();
        executor.execute(new ExecuteResultHandlerAdapter() {
            @Override
            public boolean nextRow(Object[] row)
            {
                result.add(row);
                return false;
            }

            @Override
            public int getMaxRowsToFetch()
            {
                return 1;
            }
        }, SQL, parameters);

        return result.isEmpty() ? null : result.get(0);
    }

    public String[] topAsString(String string, Object... parameters) throws SQLException
    {
        final Object []rowAsObjects = top(string, parameters);
        final String []rowAsStrings = new String[rowAsObjects.length];
        for(int i = 0; i < rowAsObjects.length; i++)
            rowAsStrings[i] = rowAsObjects[i] != null ? rowAsObjects[i].toString() : null;
        return rowAsStrings;
    }

    public List<Object> leftColumn(String SQL, Object... parameters) throws SQLException
    {
        List<Object[]> allRows = query(SQL, parameters);
        List<Object> result = new ArrayList<Object>();
        for(Object[] row: allRows)
            result.add(row[0]);
        return result;
    }

    public List<String> leftColumnAsString(String SQL, Object... parameters) throws SQLException
    {
        List<Object> leftColumn = leftColumn(SQL, parameters);
        List<String> result = new ArrayList<String>(leftColumn.size());
        for(Object value: leftColumn) {
            if(value == null)
                result.add(null);
            else
                result.add(value.toString());
        }
        return result;
    }

    public Object topLeftValue(String SQL, Object... parameters) throws SQLException
    {
        Object []row = top(SQL, parameters);
        if(row == null)
            return null;
        else
            return row[0];
    }

    public String topLeftValueAsString(String SQL, Object... parameters) throws SQLException
    {
        final Object value = topLeftValue(SQL, parameters);
        if(value == null)
            return null;
        else
            return value.toString();
    }

    public Integer topLeftValueAsInt(String SQL, Object... parameters) throws SQLException
    {
        Object value = topLeftValue(SQL, parameters);
        if(value == null)
            return null;
        else
            return Integer.parseInt(value.toString());
    }

    public Long topLeftValueAsLong(String SQL, Object... parameters) throws SQLException
    {
        Object value = topLeftValue(SQL, parameters);
        if(value == null)
            return null;
        else
            return Long.parseLong(value.toString());
    }

    public BigInteger topLeftValueAsBigInteger(String SQL, Object... parameters) throws SQLException
    {
        Object value = topLeftValue(SQL, parameters);
        if(value == null)
            return null;
        else
            return new BigInteger(value.toString());
    }
}
