/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.storage.implementation.sql.connection.hikari;

import com.google.common.collect.ImmutableList;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import me.lucko.luckperms.common.plugin.logging.PluginLogger;
import me.lucko.luckperms.common.storage.StorageMetadata;
import me.lucko.luckperms.common.storage.implementation.sql.connection.ConnectionFactory;
import me.lucko.luckperms.common.storage.misc.StorageCredentials;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Abstract {@link ConnectionFactory} using a {@link HikariDataSource}.
 */
public abstract class HikariConnectionFactory implements ConnectionFactory {
    private final StorageCredentials configuration;
    private HikariDataSource hikari;

    public HikariConnectionFactory(StorageCredentials configuration) {
        this.configuration = configuration;
    }

    /**
     * Gets the default port used by the database
     *
     * @return the default port
     */
    protected abstract String defaultPort();

    /**
     * Configures the {@link HikariConfig} with the relevant database properties.
     *
     * <p>Each driver does this slightly differently...</p>
     *
     * @param config       the hikari config
     * @param address      the database address
     * @param port         the database port
     * @param databaseName the database name
     * @param username     the database username
     * @param password     the database password
     */
    protected abstract void configureDatabase(HikariConfig config, String address, String port, String databaseName, String username, String password);

    /**
     * Allows the connection factory instance to override certain properties before they are set.
     *
     * @param properties the current properties
     */
    protected void overrideProperties(Map<String, Object> properties) {
        // https://github.com/brettwooldridge/HikariCP/wiki/Rapid-Recovery
        properties.putIfAbsent("socketTimeout", String.valueOf(TimeUnit.SECONDS.toMillis(30)));
    }

    /**
     * Sets the given connection properties onto the config.
     *
     * @param config     the hikari config
     * @param properties the properties
     */
    protected void setProperties(HikariConfig config, Map<String, Object> properties) {
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            config.addDataSourceProperty(property.getKey(), property.getValue());
        }
    }

    /**
     * Called after the Hikari pool has been initialised
     */
    protected void postInitialize() {

    }

    @Override
    public void init(LuckPermsPlugin plugin) {
        HikariConfig config;
        try {
            config = new HikariConfig();
        } catch (LinkageError e) {
            handleClassloadingError(e, plugin);
            throw e;
        }

        // set pool name so the logging output can be linked back to us
        config.setPoolName("luckperms-hikari");

        // get the database info/credentials from the config file
        String[] addressSplit = this.configuration.getAddress().split(":");
        String address = addressSplit[0];
        String port = addressSplit.length > 1 ? addressSplit[1] : defaultPort();

        // LuckPerms Rivrs - Start
        String username = this.configuration.getUsername();
        String password = this.configuration.getPassword();

        // get the database info/credentials from the environment variables
        String host = System.getenv("MARIADB_HOST");
        String portParsed = System.getenv("MARIADB_PORT");
        String usernameParsed = System.getenv("MARIADB_USER");
        String passwordParsed = System.getenv("MARIADB_PASSWORD");

        if (host != null && portParsed != null && username != null && password != null) {
            address = host;
            port = portParsed;
            username = usernameParsed;
            password = passwordParsed;
        }

        // allow the implementation to configure the HikariConfig appropriately with these values
        try {
            configureDatabase(config, address, port, this.configuration.getDatabase(), username, password);
        } catch (NoSuchMethodError e) {
            handleClassloadingError(e, plugin);
        }
        // LuckPerms Rivrs - End

        // get the extra connection properties from the config
        Map<String, Object> properties = new HashMap<>(this.configuration.getProperties());

        // allow the implementation to override/make changes to these properties
        overrideProperties(properties);

        // set the properties
        setProperties(config, properties);

        // configure the connection pool
        config.setMaximumPoolSize(this.configuration.getMaxPoolSize());
        config.setMinimumIdle(this.configuration.getMinIdleConnections());
        config.setMaxLifetime(this.configuration.getMaxLifetime());
        config.setKeepaliveTime(this.configuration.getKeepAliveTime());
        config.setConnectionTimeout(this.configuration.getConnectionTimeout());

        // don't perform any initial connection validation - we subsequently call #getConnection
        // to setup the schema anyways
        config.setInitializationFailTimeout(-1);

        this.hikari = new HikariDataSource(config);

        postInitialize();
    }

    @Override
    public void shutdown() {
        if (this.hikari != null) {
            this.hikari.close();
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (this.hikari == null) {
            throw new SQLException("Unable to get a connection from the pool. (hikari is null)");
        }

        Connection connection = this.hikari.getConnection();
        if (connection == null) {
            throw new SQLException("Unable to get a connection from the pool. (getConnection returned null)");
        }

        return connection;
    }

    @Override
    public StorageMetadata getMeta() {
        StorageMetadata metadata = new StorageMetadata();

        boolean success = true;
        long start = System.currentTimeMillis();

        try (Connection c = getConnection()) {
            try (Statement s = c.createStatement()) {
                s.execute("/* ping */ SELECT 1");
            }
        } catch (SQLException e) {
            success = false;
        }

        if (success) {
            int duration = (int) (System.currentTimeMillis() - start);
            metadata.ping(duration);
        }

        metadata.connected(success);
        return metadata;
    }

    // dumb plugins seem to keep doing stupid stuff with shading of SLF4J and Log4J.
    // detect this and print a more useful error message.
    private static void handleClassloadingError(Throwable throwable, LuckPermsPlugin plugin) {
        List<String> noteworthyClasses = ImmutableList.of(
                "org.slf4j.LoggerFactory",
                "org.slf4j.ILoggerFactory",
                "org.apache.logging.slf4j.Log4jLoggerFactory",
                "org.apache.logging.log4j.spi.LoggerContext",
                "org.apache.logging.log4j.spi.AbstractLoggerAdapter",
                "org.slf4j.impl.StaticLoggerBinder",
                "org.slf4j.helpers.MessageFormatter"
        );

        PluginLogger logger = plugin.getLogger();
        logger.warn("A " + throwable.getClass().getSimpleName() + " has occurred whilst initialising Hikari. This is likely due to classloading conflicts between other plugins.");
        logger.warn("Please check for other plugins below (and try loading LuckPerms without them installed) before reporting the issue.");

        for (String className : noteworthyClasses) {
            Class<?> clazz;
            try {
                clazz = Class.forName(className);
            } catch (Exception e) {
                continue;
            }

            ClassLoader loader = clazz.getClassLoader();
            String loaderName;
            try {
                loaderName = plugin.getBootstrap().identifyClassLoader(loader) + " (" + loader.toString() + ")";
            } catch (Throwable e) {
                loaderName = loader.toString();
            }

            logger.warn("Class " + className + " has been loaded by: " + loaderName);
        }
    }
}
