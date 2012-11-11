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

import com.googlecode.jdbw.orm.Identifiable;
import java.util.HashMap;
import java.util.Map;

class TableMappings {
    private final Map<Class, TableMapping> mappings;

    TableMappings() {
        mappings = new HashMap<Class, TableMapping>();
    }
    
    <U, T extends Identifiable<U>> TableMapping<U, T> get(Class<T> objectType) {
        synchronized(mappings) {
            return mappings.get(objectType);
        }
    }
    
    <U, T extends Identifiable<U>> boolean add(TableMapping<U, T> mapping) {
        synchronized(mappings) {
            if(!mappings.containsKey(mapping.getObjectType())) {
                mappings.put(mapping.getObjectType(), mapping);
                return true;
            }
            else {
                return false;
            }
        }
    }
}
