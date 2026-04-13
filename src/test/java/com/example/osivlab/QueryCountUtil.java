package com.example.osivlab;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

/**
 * Utility for counting JDBC queries using Hibernate Statistics.
 * Requires hibernate.generate_statistics=true in application config.
 */
public final class QueryCountUtil {

    private final Statistics statistics;

    public QueryCountUtil(SessionFactory sessionFactory) {
        this.statistics = sessionFactory.getStatistics();
        this.statistics.setStatisticsEnabled(true);
    }

    /**
     * Clears statistics and starts fresh counting.
     */
    public void clear() {
        statistics.clear();
    }

    /**
     * Returns the number of SQL queries executed since last clear().
     */
    public long getQueryCount() {
        return statistics.getQueryExecutionCount()
                + statistics.getEntityLoadCount()
                + statistics.getCollectionLoadCount();
    }

    /**
     * Returns the total number of JDBC statements prepared since last clear().
     * This is the most accurate measure of actual DB round-trips.
     */
    public long getPrepareStatementCount() {
        return statistics.getPrepareStatementCount();
    }

    /**
     * Returns the number of entities fetched from the database.
     */
    public long getEntityFetchCount() {
        return statistics.getEntityFetchCount();
    }

    /**
     * Returns the number of collections fetched.
     */
    public long getCollectionFetchCount() {
        return statistics.getCollectionFetchCount();
    }

    /**
     * Returns a formatted summary of all relevant statistics.
     */
    public String getSummary() {
        return String.format(
                "Queries: prepared=%d, entityFetch=%d, collectionFetch=%d, queryExec=%d",
                statistics.getPrepareStatementCount(),
                statistics.getEntityFetchCount(),
                statistics.getCollectionFetchCount(),
                statistics.getQueryExecutionCount()
        );
    }
}
