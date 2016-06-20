/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.db.client.constraint.impl;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Statement;
import com.emc.storageos.db.client.constraint.Constraint;
import com.emc.storageos.db.client.constraint.ConstraintDescriptor;
import com.emc.storageos.db.client.impl.ColumnField;
import com.emc.storageos.db.client.impl.DbClientContext;
import com.emc.storageos.db.client.impl.IndexColumnName;
import com.emc.storageos.db.client.impl.IndexColumnNameSerializer;
import com.emc.storageos.db.exceptions.DatabaseException;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.model.Column;
import com.netflix.astyanax.model.ColumnList;
import com.netflix.astyanax.query.RowQuery;
import com.netflix.astyanax.serializers.CompositeRangeBuilder;

/**
 * Abstract base for all containment queries
 */
public abstract class ConstraintImpl implements Constraint {
    private static final Logger log = LoggerFactory.getLogger(ConstraintImpl.class);
    private static final int DEFAULT_PAGE_SIZE = 100;

    private ConstraintDescriptor constraintDescriptor;

    protected String startId;
    protected int pageCount = DEFAULT_PAGE_SIZE;
    protected boolean returnOnePage;
    protected DbClientContext dbClientContext;
    protected Keyspace _keyspace;

    public ConstraintImpl(Object... arguments) {
        ColumnField field = null;
        int cfPosition = 0;
        int i = 0;

        List args = new ArrayList();
        for (Object argument : arguments) {
            i++;
            if (argument instanceof ColumnField) {
                field = (ColumnField) argument;
                cfPosition = i;
                continue;
            }

            args.add(argument);
        }

        // TODO: remove this once TimeConstraintImpl has been reworked to work over geo-queries
        if (this instanceof TimeConstraintImpl) {
            return;
        }

        if (field == null) {
            throw new IllegalArgumentException("ColumnField should be in the constructor arguments");
        }

        String dataObjClassName = field.getDataObjectType().getName();
        String fieldName = field.getName();

        constraintDescriptor = new ConstraintDescriptor();
        constraintDescriptor.setConstraintClassName(this.getClass().getName());
        constraintDescriptor.setDataObjectClassName(dataObjClassName);
        constraintDescriptor.setColumnFieldName(fieldName);
        constraintDescriptor.setColumnFieldPosition(cfPosition);
        constraintDescriptor.setArguments(args);
    }

    @Override
    public ConstraintDescriptor toConstraintDescriptor() {
        return constraintDescriptor;
    }

    public abstract boolean isValid();
    
    public void setStartId(URI startId) {
        if (startId != null) {
            this.startId = startId.toString();
        }

        this.returnOnePage = true;
    }

    public void setPageCount(int pageCount) {
        if (pageCount > 0) {
            this.pageCount = pageCount;
        }
    }

    @Override
    public <T> void execute(final Constraint.QueryResult<T> result) {
        try {
            if (returnOnePage) {
                queryOnePage(result);
                return;
            }
        } catch (ConnectionException e) {
            log.info("Query failed e=", e);
            throw DatabaseException.retryables.connectionFailed(e);
        }

        queryWithAutoPaginate(genQueryStatement(), result, this);
    }

    protected abstract <T> void queryOnePage(final QueryResult<T> result) throws ConnectionException;

    protected RowQuery<String, IndexColumnName> genQuery() {
        return null;
    }
    
    protected Statement genQueryStatement() {
        return null;
    }

    protected <T> void queryWithAutoPaginate(Statement statement, final QueryResult<T> result,
            final ConstraintImpl constraint) {
        
        QueryHitIterator<T> it = new QueryHitIterator<T>(dbClientContext , statement) {
            @Override
            protected T createQueryHit(IndexColumnName column) {
                return constraint.createQueryHit(result, column);
            }
        };
        it.prime();
        result.setResult(it);
    }

    protected abstract URI getURI(IndexColumnName col);
    
    protected <T> T createQueryHit(final QueryResult<T> result, IndexColumnName col) {
        return null;
    }

    protected <T> void queryOnePageWithoutAutoPaginate(RowQuery<String, IndexColumnName> query, String prefix, final QueryResult<T> result)
            throws ConnectionException {

        CompositeRangeBuilder builder = IndexColumnNameSerializer.get().buildRange()
                .greaterThanEquals(prefix)
                .lessThanEquals(prefix)
                .reverse() // last column comes only
                .limit(1);

        query.withColumnRange(builder);

        ColumnList<IndexColumnName> columns = query.execute().getResult();

        List<T> ids = new ArrayList();
        if (columns.isEmpty()) {
            result.setResult(ids.iterator());
            return; // not found
        }

        Column<IndexColumnName> lastColumn = columns.getColumnByIndex(0);

        String endId = lastColumn.getName().getTwo();

        builder = IndexColumnNameSerializer.get().buildRange();

        if (startId == null) {
            // query first page
            builder.greaterThanEquals(prefix)
                    .lessThanEquals(prefix)
                    .limit(pageCount);

        } else {
            builder.withPrefix(prefix)
                    .greaterThan(startId)
                    .lessThanEquals(endId)
                    .limit(pageCount);
        }

        query = query.withColumnRange(builder);

        columns = query.execute().getResult();

        for (Column<IndexColumnName> col : columns) {
            T obj = createQueryHit(result, col.getName());
            if (!ids.contains(obj)) {
                ids.add(createQueryHit(result, col.getName()));
            }
        }

        result.setResult(ids.iterator());
    }

    protected <T> void queryOnePageWithAutoPaginate(RowQuery<String, IndexColumnName> query, String prefix, final QueryResult<T> result)
            throws ConnectionException {
        CompositeRangeBuilder range = IndexColumnNameSerializer.get().buildRange()
                .greaterThanEquals(prefix)
                .lessThanEquals(prefix)
                .limit(pageCount);
        query.withColumnRange(range);

        queryOnePageWithAutoPaginate(query, result);
    }

    protected <T> void queryOnePageWithAutoPaginate(RowQuery<String, IndexColumnName> query, final QueryResult<T> result)
            throws ConnectionException {
        boolean start = false;
        List<T> ids = new ArrayList();
        int count = 0;

        query.autoPaginate(true);

        ColumnList<IndexColumnName> columns;

        while (count < pageCount) {
            columns = query.execute().getResult();

            if (columns.isEmpty())
            {
                break; // reach the end
            }

            for (Column<IndexColumnName> col : columns) {
                if (startId == null) {
                    start = true;
                } else if (startId.equals(getURI(col.getName()).toString())) {
                    start = true;
                    continue;
                }

                if (start) {
                    T obj = createQueryHit(result, col.getName());
                    if (!ids.contains(obj)) {
                        ids.add(obj);
                    }
                    count++;
                }
            }
        }
        result.setResult(ids.iterator());
    }
    
    protected <T> void queryOnePageWithAutoPaginate(Statement queryStatement, final QueryResult<T> result) {
        boolean start = false;
        List<T> ids = new ArrayList<T>();
        int count = 0;

        ResultSet resultSet = dbClientContext.getSession().execute(queryStatement);

        for (Row row : resultSet) {
            IndexColumnName indexColumnName = new IndexColumnName(row.getString(1), 
                    row.getString(2), 
                    row.getString(3),
                    row.getString(4),
                    row.getUUID(5),
                    row.getBytes(6));
            
            if (startId == null) {
                start = true;
            } else if (startId.equals(getURI(indexColumnName).toString())) {
                start = true;
                continue;
            }

            if (start) {
                T obj = createQueryHit(result, indexColumnName);
                if (!ids.contains(obj)) {
                    ids.add(obj);
                }
                count++;
                
                if (count >= pageCount) {
                    break;
                }
            }
        }
        result.setResult(ids.iterator());
    }

    @Override
    public void setDbClientContext(DbClientContext dbClientContext) {
        this.dbClientContext = dbClientContext;
    }
    
    @Override
    public void setKeyspace(Keyspace keyspace) {
        _keyspace = keyspace;
    }
}
