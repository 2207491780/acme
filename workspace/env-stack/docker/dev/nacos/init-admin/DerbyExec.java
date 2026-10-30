import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 一次性运维：对 Nacos 内置 derby 执行 SQL 文件（一行一条），用完即删 */
public class DerbyExec {
    public static void main(String[] args) throws Exception {
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        List<String> sqls = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("--")) sqls.add(t);
        }
        try (Connection c = DriverManager.getConnection("jdbc:derby:/home/nacos/data/derby-data");
             Statement s = c.createStatement()) {
            for (String sql : sqls) {
                try {
                    boolean hasRs = s.execute(sql);
                    System.out.println("OK: " + sql);
                    if (hasRs) {
                        try (ResultSet rs = s.getResultSet()) {
                            ResultSetMetaData m = rs.getMetaData();
                            while (rs.next()) {
                                StringBuilder sb = new StringBuilder("  ");
                                for (int i = 1; i <= m.getColumnCount(); i++) {
                                    sb.append(m.getColumnName(i)).append('=').append(rs.getString(i)).append(' ');
                                }
                                System.out.println(sb);
                            }
                        }
                    }
                } catch (SQLException e) {
                    System.out.println("FAIL: " + sql + " -> " + e.getMessage());
                }
            }
        }
    }
}
