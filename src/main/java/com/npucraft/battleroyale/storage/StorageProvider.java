package com.npucraft.battleroyale.storage;
import java.sql.*;
/** A dedicated database worker owns every connection; gameplay code never sees JDBC. */
public interface StorageProvider {Connection connect() throws SQLException;String type();}
