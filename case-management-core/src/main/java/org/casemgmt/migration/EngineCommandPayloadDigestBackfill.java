package org.casemgmt.migration;

import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

/** Streams retained legacy command CLOBs through the same full UTF-8 SHA-256 policy as Java. */
public final class EngineCommandPayloadDigestBackfill implements CustomTaskChange {

    @Override
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection jdbcConnection)) {
            throw new CustomChangeException("Command payload digest backfill requires JDBC");
        }
        try {
            var connection = jdbcConnection.getUnderlyingConnection();
            try (var select = connection.prepareStatement("""
                    SELECT ID_, PAYLOAD_JSON_ FROM CM_ENGINE_COMMAND
                    WHERE ORIGINAL_STATUS_ IS NOT NULL AND PAYLOAD_DIGEST_ IS NULL
                    FOR UPDATE
                    """);
                 var update = connection.prepareStatement("""
                    UPDATE CM_ENGINE_COMMAND SET PAYLOAD_DIGEST_=?
                    WHERE ID_=? AND ORIGINAL_STATUS_ IS NOT NULL AND PAYLOAD_DIGEST_ IS NULL
                    """);
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    Reader payload = rows.getCharacterStream(2);
                    update.setString(1, hashUtf8(payload == null
                            ? new StringReader("{}") : payload));
                    update.setString(2, rows.getString(1));
                    update.addBatch();
                }
                update.executeBatch();
            }
        } catch (SQLException | IOException ex) {
            throw new CustomChangeException("Unable to hash retained command payloads", ex);
        }
    }

    static String hashUtf8(Reader source) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
        try (var sink = new DigestOutputStream(OutputStream.nullOutputStream(), digest);
             var utf8 = new OutputStreamWriter(sink, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            for (int read; (read = source.read(buffer)) >= 0; ) {
                if (read > 0) utf8.write(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Override
    public String getConfirmationMessage() {
        return "Retained engine command payload digests backfilled";
    }

    @Override
    public void setUp() throws SetupException {
        // No external resources or privileges are required.
    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {
        // No changelog-relative files are used.
    }

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }
}
