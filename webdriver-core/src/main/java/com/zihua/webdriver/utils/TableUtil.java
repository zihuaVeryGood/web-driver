package com.zihua.webdriver.utils;

import cn.hutool.core.annotation.AnnotationUtil;
import cn.hutool.core.date.BetweenFormater;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.resource.NoResourceException;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.ClassUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.db.Entity;
import cn.hutool.db.ds.simple.SimpleDataSource;
import cn.hutool.db.handler.EntityListHandler;
import cn.hutool.db.sql.SqlExecutor;
import cn.hutool.extra.template.Template;
import cn.hutool.extra.template.TemplateConfig;
import cn.hutool.extra.template.TemplateEngine;
import cn.hutool.extra.template.TemplateUtil;
import cn.hutool.setting.dialect.Props;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.google.common.base.CaseFormat;
import com.zihua.webdriver.annotation.DatabaseName;
import com.zihua.webdriver.annotation.FieldName;
import com.zihua.webdriver.database.FieldVo;
import com.zihua.webdriver.enums.JdbcTypeEnum;
import com.zihua.webdriver.enums.OtherEnum;
import com.zihua.webdriver.exception.NoConfigException;
import org.apache.commons.lang3.StringUtils;
import org.assertj.core.util.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @ClassName TableUtls
 * @Description TODO
 * @Author 刘子华
 * @Date 2020/2/1 14:26
 */
@Component
public class TableUtil {

    private static Logger log = LoggerFactory.getLogger(TableUtil.class);

    private static Connection conn = null;

    private static String tableName;

    private static String databaseName;

    private static Prop config;


    /**
     *  创建数据源
     * @param database_name 数据库名称
     * @param port          端口号 例如:localhost,127.0.0.1
     * @param user          用户名
     * @param pass          密码
     * @return  一个简单的数据源
     * @throws SQLException
     */
    private static DataSource initialDataSource(String database_name, String port, String user, String pass) throws SQLException {
        String url = StrUtil.format("jdbc:mysql://{}:3306/{}?useSSL=false&useUnicode=true&characterEncoding=utf-8&zeroDateTimeBehavior=convertToNull&autoReconnect=true&serverTimezone=GMT",port, database_name);
        return new SimpleDataSource(url, user, pass);
    }


    public static void tableGenerateByClass(Class<?> clazz) throws SQLException, IOException {
        DateTime start = DateUtil.date();

        // 判断是否存在 "@DatabaseName" 注解，通过反射获取数据库名。
        // 如果没指定"@DatabaseName"则会寻找config.propertes配置文件。
        if (null == AnnotationUtil.getAnnotation(clazz, DatabaseName.class)) {
            try {
                config = new Prop("config.properties");
                databaseName = converToUnderScore(config.getProperty("database-name"));
            } catch (NoResourceException e) {
                log.error("找不到配置文件 \"config.propeties\"：.{}", e);
                throw new RuntimeException("找不到配置文件 \"config.propeties\".");
            } catch (NullPointerException e) {
                log.error("找不到配置项 database-name :{}.", e);
                throw new RuntimeException("找不到配置项database-name.");
            }
        } else {
            databaseName = converToUnderScore(clazz.getAnnotation(DatabaseName.class).value());
        }



        // 动态创建数据源
        try {
            Props spring_config = new Props("application.properties");
            String port = StrUtil.isBlank(config.getProperty("port")) ?
                    "localhost" : config.getProperty("port");
            DataSource ds = initialDataSource(databaseName, port, spring_config.getProperty("spring.datasource.username"), spring_config.getProperty("spring.datasource.password"));
            conn = ds.getConnection();
        } catch (NoResourceException e) {
            log.error("找不到配置文件 \"application.propeties\"：.{}", e);
            throw new RuntimeException("找不到配置文件 \"application.propeties\".");
        }




        // 判断是否存在 "@TableName" 注解，通过反射获取表名，如果value属性为空则表名为实体类名
        if (null == AnnotationUtil.getAnnotation(clazz, TableName.class)) {
            log.error("\"{}\" 没有找到 \"@TableName\" 注解.", clazz.getName());
            throw new RuntimeException(StrUtil.format("\"{}\" 没有找到 \"@TableName\" 注解.", clazz.getName()));
        }
        String table_name = StringUtils.isBlank(clazz.getAnnotation(TableName.class).value()) ?
                clazz.getSimpleName() : clazz.getAnnotation(TableName.class).value();
        tableName = converToUnderScore(table_name);


        // 根据数据源和表名创建表
        boolean isCreated = create(ReflectUtil.getFieldMap(clazz));

        log.info("表\"{}\"创建完毕,耗时:{}.", clazz.getSimpleName(), DateUtil.formatBetween(start, DateUtil.date(), BetweenFormater.Level.SECOND));
    }

    public static void tableGenerateByPackage() throws SQLException, IOException {
        try {
            config = new Prop("config.properties");    
        } catch (Exception e) {
            log.error("没有正确的读取到配置项.");
            throw new NoConfigException("配置项package-scan找不到.");
        }
        String path = config.getProperty("package-scan");
        if (StrUtil.isBlank(path)) {
            throw new RuntimeException("没有找到 package-scan 的路径.");
        }
        tableGenerateByPackage(path);
    }

    
    
    public static void tableGenerateByPackage(String packagePath) throws SQLException, IOException {
        Set<Class<?>> set = ClassUtil.scanPackage(packagePath);
        for (Class<?> clazz : set) {
            if (-1 == StrUtil.lastIndexOfIgnoreCase(clazz.getName(), "$")) {
                tableGenerateByClass(clazz);
            }
        }
    }


    
    
    /**
     *      1. 判断数据库是否存在这张表
     *      2. 不存在则创建
     *      3. 存在则报错返回
     * @param columns   字段组
     */
    private static boolean create(Map<String, Field> columns) throws SQLException, IOException {

        // 查询表是否存在，存在则返回
        String sql = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        List<Entity> entityList = SqlExecutor.query(conn, sql, new EntityListHandler(), databaseName, tableName);
        if (entityList.size() != 0) {
            log.error("表\"{}\"已经存在", tableName);
            return false;
        }

        // 准备Beatl引擎
        TemplateEngine engine = TemplateUtil.createEngine(new TemplateConfig("templates", TemplateConfig.ResourceMode.CLASSPATH));
        Template template = engine.getTemplate("/sql-template.btl");


        // 字段集合
        List<FieldVo> list = Lists.newArrayList();
        // 标识主键，后面要用到
        AtomicReference<String> primaryKey = new AtomicReference("");

        columns.forEach((k,v) -> {
            FieldVo field = new FieldVo();

            field.setName(converToUnderScore(v.getName()));
            field.setType(JdbcTypeEnum.UNKNOW.get(v.getType()).getJdbcType().TYPE_FORMAT_NAME);
            if (v.isAnnotationPresent(TableId.class)) {
                if (v.isAnnotationPresent(FieldName.class)) {
                    FieldName fieldNameAnno =  AnnotationUtil.getAnnotation(v, FieldName.class);
                    primaryKey.set(converToUnderScore(v.getName()));
                    field.setOther(OtherEnum.PRIMARY_KEY.getValue());
                    field.setLength(fieldNameAnno.length());
                    if (StrUtil.isNotBlank(fieldNameAnno.comment())) {
                        field.setOther(field.getOther() + " COMMENT '" + fieldNameAnno.comment() + "'");
                    }
                    if (v.getType() == String.class && fieldNameAnno.length() == 0) {
                        field.setType("TEXT");
                    }
                    list.add(0, field);
                } else {
                    primaryKey.set(converToUnderScore(v.getName()));
                    field.setOther(OtherEnum.PRIMARY_KEY.getValue());
                    field.setLength(JdbcTypeEnum.UNKNOW.get(v.getType()).getJdbcType().TYPE_DEFAULT_LENGTH);
                    list.add(0, field);
                }
            } else if (v.isAnnotationPresent(FieldName.class)) {
                FieldName fieldNameAnno =  AnnotationUtil.getAnnotation(v, FieldName.class);
                if (v.getType() == String.class && fieldNameAnno.length() == 0) {
                    field.setType("TEXT");
                }
                if (fieldNameAnno.other().equals(OtherEnum.PRIMARY_KEY)) {
                    primaryKey.set(converToUnderScore(v.getName()));
                    field.setOther(OtherEnum.PRIMARY_KEY.getValue());
                    field.setLength(fieldNameAnno.length());
                    if (StrUtil.isNotBlank(fieldNameAnno.comment())) {
                        field.setOther(field.getOther() + " COMMENT '" + fieldNameAnno.comment() + "'");
                    }
                    list.add(0, field);
                } else {
                    field.setOther(fieldNameAnno.other().getValue());
                    field.setLength(fieldNameAnno.length());
                    if (StrUtil.isNotBlank(fieldNameAnno.comment())) {
                        field.setOther(field.getOther() + " COMMENT '" + fieldNameAnno.comment() + "'");
                    }
                    list.add(field);
                }
            } else {
                field.setOther(OtherEnum.NOT_PRIMARY_KEY.getValue());
                field.setLength(JdbcTypeEnum.UNKNOW.get(v.getType()).getJdbcType().TYPE_DEFAULT_LENGTH);
                list.add(field);
            }
        });


        // 渲染模板 填充字段
        String comment = config.getProperty(StrUtil.format("table.{}.comment", tableName));
        if (StrUtil.isNotBlank(comment)) {
            comment = StrUtil.format("COMMENT '{}'", comment);
        } else {
            comment = "";
        }
        String sql_template = template.render(Dict.create().set("fields", list).set("tableName", tableName).set("tableComment", comment).set("primaryKey", primaryKey.get()));


        // 根据配置判断是否显示sql语句
        if (config.getBool("sql.show")) {
            log.info("SQL：\n{}", sql_template);
        }


        // 执行模板SQL
        return SqlExecutor.execute(conn, sql_template) == 0 ? false : true;
    }


    /**
     *  将文本转换成 "Camel" 命名规范形式，常见用于转换成数据库字段名。
     * @param text 待转换的文本
     * @return 转换后的文本
     *  LOWER_CAMEL -> lowerCamel
     */
    private static String converToCamel(String text) {
        return CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, text);
    }

    /**
     *  将文本转换成 "UnderScore" 命名规范形式，常见用于转换成数据表名。
     * @param text  待转换的文本
     * @return  转换后的文本
     *  lowerCamel -> lower_underscore
     */
    private static String converToUnderScore(String text) {
        return CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, text);
    }
}

