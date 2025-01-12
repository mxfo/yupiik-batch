/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.batch.runtime.component;

import io.yupiik.batch.runtime.documentation.Component;
import io.yupiik.batch.runtime.iterator.RespectingContractIterator;
import io.yupiik.batch.runtime.sql.SQLFunction;
import io.yupiik.batch.runtime.sql.SQLSupplier;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;

@Component("""
        Enables to extract data from a SQL query.
                
        A custom mapper will be called for each `ResultSet` line to convert current row in an object passed to the rest of the `BatchChain`.""")
public class SQLQuery<T> extends RespectingContractIterator<T> implements Iterator<T>, AutoCloseable {
    public SQLQuery(final SQLSupplier<Connection> connectionSupplier, final String query,
                    final SQLFunction<ResultSet, T> mapper) {
        this(connectionSupplier, query, mapper, 0);
    }

    public SQLQuery(final SQLSupplier<Connection> connectionSupplier, final String query,
                    final SQLFunction<ResultSet, T> mapper, final int fetchSize) {
        super(new Impl<>(connectionSupplier, query, mapper, fetchSize));
    }

    private static class Impl<T> implements Iterator<T>, AutoCloseable {
        private final String query;
        private final SQLFunction<ResultSet, T> mapper;

        private Connection connection;
        private Statement statement;
        private ResultSet resultSet;
        private final int fetchSize;

        private Impl(final SQLSupplier<Connection> connectionSupplier, final String query,
                     final SQLFunction<ResultSet, T> mapper) {
            this(connectionSupplier, query, mapper, 0);
        }

        private Impl(final SQLSupplier<Connection> connectionSupplier, final String query,
                     final SQLFunction<ResultSet, T> mapper, final int fetchSize) {
            this.query = query;
            this.mapper = mapper;
            this.fetchSize = fetchSize;
            try {
                this.connection = connectionSupplier.get();
            } catch (final SQLException throwables) {
                throw new IllegalStateException(throwables);
            }
        }

        @Override
        public boolean hasNext() { // RespectingContractIterator enables to call it multiple times consequently when needed
            if (statement == null) {
                try {
                    this.statement = connection.createStatement();
                    if (fetchSize > 0) {
                        this.statement.setFetchSize(fetchSize);
                    }
                    this.resultSet = statement.executeQuery(query);
                } catch (final SQLException throwables) {
                    throw new IllegalStateException(throwables);
                }
            }
            try {
                return resultSet.next();
            } catch (final SQLException throwables) {
                throw new IllegalStateException(throwables);
            }
        }

        @Override
        public T next() {
            try {
                return mapper.apply(resultSet);
            } catch (final SQLException throwables) {
                throw new IllegalStateException(throwables);
            }
        }

        @Override
        public void close() {
            final var error = new IllegalStateException("An error occurred closing " + getClass());
            Stream.of(resultSet, statement, connection)
                    .filter(Objects::nonNull)
                    .forEach(it -> {
                        try {
                            it.close();
                        } catch (final Exception e) {
                            error.addSuppressed(e);
                        }
                    });
            connection = null;
            statement = null;
            resultSet = null;
            if (error.getSuppressed().length > 0) {
                throw error;
            }
        }
    }

}
