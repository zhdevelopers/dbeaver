/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2020 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.postgresql;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDValueHandler;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.struct.DBSDataType;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;
import org.jkiss.dbeaver.model.struct.DBSTypedObjectEx;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public class PostgreValueParser {

    private static final Log log = Log.getLog(PostgreValueParser.class);

    // todo: is not working with multidimensional arrays and structures
    public static Object convertStringToValue(DBCSession session, DBSTypedObject itemType, String string, boolean unescape) throws DBCException {
        if (itemType.getDataKind() == DBPDataKind.ARRAY) {
            if (CommonUtils.isEmpty(string)) {
                return new Object[0];
            } else if (string.startsWith("{") && string.endsWith("}")) {
                DBSDataType arrayDataType = itemType instanceof DBSDataType ? (DBSDataType) itemType : ((DBSTypedObjectEx) itemType).getDataType();
                try {
                    DBSDataType componentType = arrayDataType.getComponentType(session.getProgressMonitor());
                    if (componentType == null) {
                        log.error("Can't get component type from array '" + itemType.getFullTypeName() + "'");
                        return null;
                    } else {
                        String[] itemStrings = parseObjectString(string.substring(1, string.length() - 1));
                        Object[] itemValues = new Object[itemStrings.length];
                        for (int i = 0; i < itemStrings.length; i++) {
                            itemValues[i] = convertStringToValue(session, componentType, itemStrings[i], unescape);
                        }
                        return itemValues;
                    }
                } catch (Exception e) {
                    throw new DBCException("Error extracting array '" + itemType.getFullTypeName() + "' items", e);
                }
            } else {
                log.error("Unsupported array string: '" + string + "'");
                return null;
            }
        }
        if (CommonUtils.isEmpty(string)) {
            return convertStringToSimpleValue(session, itemType, string);
        }
        try {
            switch (itemType.getTypeID()) {
                case Types.BOOLEAN:
                    return string.length() > 0 && Character.toLowerCase(string.charAt(0)) == 't'; //todo: add support of alternatives to "true/false"
                case Types.TINYINT:
                    return Byte.parseByte(string);
                case Types.SMALLINT:
                    return Short.parseShort(string);
                case Types.INTEGER:
                    return Integer.parseInt(string);
                case Types.BIGINT:
                    return Long.parseLong(string);
                case Types.FLOAT:
                    return Float.parseFloat(string);
                case Types.REAL:
                case Types.DOUBLE:
                    return Double.parseDouble(string);
                default: {
                    return convertStringToSimpleValue(session, itemType, string);
                }
            }
        } catch (NumberFormatException e) {
            return string;
        }
    }

    private static Object convertStringToSimpleValue(DBCSession session, DBSTypedObject itemType, String string) throws DBCException {
        DBDValueHandler valueHandler = DBUtils.findValueHandler(session, itemType);
        if (valueHandler != null) {
            return valueHandler.getValueFromObject(session, itemType, string, false, false);
        } else {
            return string;
        }
    }

    public static String[] parseObjectString(String string) throws DBCException {
        if (string.isEmpty()) {
            return new String[0];
        }
        try {
            return new CSVReader(new StringReader(string)).readNext();
        } catch (IOException e) {
            throw new DBCException("Error parsing PGObject", e);
        }
    }

    public static String generateObjectString(Object[] values) throws DBCException {
        String[] line = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            final Object value = values[i];
            line[i] = value == null ? "NULL" : value.toString();
        }
        StringWriter out = new StringWriter();
        final CSVWriter writer = new CSVWriter(out);
        writer.writeNext(line);
        try {
            writer.flush();
        } catch (IOException e) {
            log.warn(e);
        }
        return "(" + out.toString().trim() + ")";
    }

    // Copied from pgjdbc array parser class
    // https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/jdbc/PgArray.java
    public static List<Object> parseArrayString(String fieldString, String delimiter) {
        List<Object> arrayList = new ArrayList<>();
        if (CommonUtils.isEmpty(fieldString)) {
            return arrayList;
        }

        int dimensionsCount = 1;
        char delim = delimiter.charAt(0);//connection.getTypeInfo().getArrayDelimiter(oid);

        if (fieldString != null) {

            char[] chars = fieldString.toCharArray();
            StringBuilder buffer = null;
            boolean insideString = false;
            boolean wasInsideString = false; // needed for checking if NULL
            // value occurred
            List<List<Object>> dims = new ArrayList<>(); // array dimension arrays
            List<Object> curArray = arrayList; // currently processed array

            // Starting with 8.0 non-standard (beginning index
            // isn't 1) bounds the dimensions are returned in the
            // data formatted like so "[0:3]={0,1,2,3,4}".
            // Older versions simply do not return the bounds.
            //
            // Right now we ignore these bounds, but we could
            // consider allowing these index values to be used
            // even though the JDBC spec says 1 is the first
            // index. I'm not sure what a client would like
            // to see, so we just retain the old behavior.
            int startOffset = 0;
            {
                if (chars[0] == '[') {
                    while (chars[startOffset] != '=') {
                        startOffset++;
                    }
                    startOffset++; // skip =
                }
            }

            for (int i = startOffset; i < chars.length; i++) {

                // escape character that we need to skip
                if (chars[i] == '\\') {
                    i++;
                } else if (!insideString && chars[i] == '{') {
                    // subarray start
                    if (dims.isEmpty()) {
                        dims.add(arrayList);
                    } else {
                        List<Object> a = new ArrayList<>();
                        List<Object> p = dims.get(dims.size() - 1);
                        p.add(a);
                        dims.add(a);
                    }
                    curArray = dims.get(dims.size() - 1);

                    // number of dimensions
                    {
                        for (int t = i + 1; t < chars.length; t++) {
                            if (Character.isWhitespace(chars[t])) {
                                continue;
                            } else if (chars[t] == '{') {
                                dimensionsCount++;
                            } else {
                                break;
                            }
                        }
                    }

                    buffer = new StringBuilder();
                    continue;
                } else if (chars[i] == '"') {
                    // quoted element
                    insideString = !insideString;
                    wasInsideString = true;
                    continue;
                } else if (!insideString && Character.isWhitespace(chars[i])) {
                    // white space
                    continue;
                } else if ((!insideString && (chars[i] == delim || chars[i] == '}'))
                    || i == chars.length - 1) {
                    // array end or element end
                    // when character that is a part of array element
                    if (chars[i] != '"' && chars[i] != '}' && chars[i] != delim && buffer != null) {
                        buffer.append(chars[i]);
                    }

                    String b = buffer == null ? null : buffer.toString();

                    // add element to current array
                    if (b != null && (!b.isEmpty() || wasInsideString)) {
                        curArray.add(!wasInsideString && b.equals("NULL") ? null : b);
                    }

                    wasInsideString = false;
                    buffer = new StringBuilder();

                    // when end of an array
                    if (chars[i] == '}') {
                        dims.remove(dims.size() - 1);

                        // when multi-dimension
                        if (!dims.isEmpty()) {
                            curArray = dims.get(dims.size() - 1);
                        }

                        buffer = null;
                    }

                    continue;
                }

                if (buffer != null) {
                    buffer.append(chars[i]);
                }
            }
        }
        return arrayList;
    }
}
