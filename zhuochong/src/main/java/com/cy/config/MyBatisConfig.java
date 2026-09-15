package com.cy.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 数据源配置。
 *
 * ★ 数据库连接**不再写死在代码里**：按「环境变量 → 配置文件(application.yml 的 zc.db.*) → 内置默认值」取值。
 *   要换数据库只需改一处：项目根目录的「环境变量配置.bat」（双击启动脚本会自动 call 它），
 *   或者设成系统环境变量；再不行才改 application.yml。
 *
 *   环境变量名（前缀 ZC_ 避免与其它程序撞名）：
 *     ZC_DB_HOST / ZC_DB_PORT / ZC_DB_NAME / ZC_DB_USER / ZC_DB_PASSWORD
 */
@Configuration
@MapperScan("com.cy.mapper")
public class MyBatisConfig {

    @Value("${zc.db.host:}")
    private String cfgHost;
    @Value("${zc.db.port:}")
    private String cfgPort;
    @Value("${zc.db.name:}")
    private String cfgName;
    @Value("${zc.db.user:}")
    private String cfgUser;
    @Value("${zc.db.password:}")
    private String cfgPassword;

    @Bean
    public DataSource dataSource() {
        String host = pick("ZC_DB_HOST", cfgHost, "127.0.0.1");
        String port = pick("ZC_DB_PORT", cfgPort, "3306");
        String name = pick("ZC_DB_NAME", cfgName, "db03");
        String user = pick("ZC_DB_USER", cfgUser, "root");
        String pass = pick("ZC_DB_PASSWORD", cfgPassword, "");

        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + name
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&allowMultiQueries=true");
        ds.setUsername(user);
        ds.setPassword(pass);
        System.out.println("[配置] 数据库 " + host + ":" + port + "/" + name + "  用户=" + user
                + ((pass == null || pass.isEmpty()) ? "  (密码为空)" : ""));
        return ds;
    }

    /** 环境变量优先 → 配置文件的 zc.db.* → 内置默认值 */
    private static String pick(String envKey, String cfgValue, String def) {
        String v = System.getenv(envKey);
        if (v == null || v.trim().isEmpty()) v = System.getProperty(envKey);
        if (v == null || v.trim().isEmpty()) v = cfgValue;
        if (v == null || v.trim().isEmpty()) v = def;
        return v == null ? "" : v.trim();
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        return factoryBean.getObject();
    }
}
