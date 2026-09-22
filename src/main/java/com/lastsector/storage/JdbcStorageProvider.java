package com.lastsector.storage;
import java.sql.*;
import java.nio.file.*;
import java.util.Properties;
public final class JdbcStorageProvider implements StorageProvider {
    private final StorageSettings settings;
    public JdbcStorageProvider(StorageSettings settings){this.settings=settings;}
    public String type(){return settings.type();}
    public Connection connect() throws SQLException {
        try {
            if(type().equals("sqlite")) {
                Class.forName("org.sqlite.JDBC");safeFile(settings.sqliteFile());Files.createDirectories(settings.sqliteFile().getParent());
                var connection=DriverManager.getConnection("jdbc:sqlite:"+settings.sqliteFile());
                try(var statement=connection.createStatement()){statement.execute("PRAGMA busy_timeout=5000");statement.execute("PRAGMA journal_mode=WAL");statement.execute("PRAGMA synchronous=FULL");statement.execute("PRAGMA foreign_keys=ON");}return connection;
            }
            Class.forName("com.mysql.cj.jdbc.Driver");var properties=new Properties();properties.setProperty("user",settings.username());properties.setProperty("password",settings.password());
            properties.setProperty("connectTimeout",String.valueOf(settings.timeoutMillis()));properties.setProperty("socketTimeout",String.valueOf(settings.timeoutMillis()));properties.setProperty("sslMode","PREFERRED");
            return DriverManager.getConnection("jdbc:mysql://"+(settings.host().contains(":")?"["+settings.host()+"]":settings.host())+":"+settings.port()+"/"+settings.database(),properties);
        } catch(Exception failure){throw new SQLException("Cannot connect recovery storage ("+type()+"); check server and configured credentials",failure instanceof SQLException sql?sql.getSQLState():"08001");}
    }
    static void safeFile(Path path) throws java.io.IOException {
        Path at=path.toAbsolutePath().normalize().getRoot();for(Path part:path.toAbsolutePath().normalize()){at=at.resolve(part);if(Files.exists(at,LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(at) || !at.toRealPath().equals(at)))throw new java.io.IOException("Unsafe database path");}
    }
}
