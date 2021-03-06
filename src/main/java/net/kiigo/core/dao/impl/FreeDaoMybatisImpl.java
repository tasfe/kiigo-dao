package net.kiigo.core.dao.impl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.kiigo.core.context.KGApplicationContextManger;
import net.kiigo.core.dao.FreeDao;
import net.kiigo.core.dao.PageCollection;
import net.kiigo.core.dao.mybatis.pagination.PagingUtils;
import net.kiigo.core.dao.mybatis.utils.BeanUtils;
import net.kiigo.core.dao.mybatis.utils.MyBatisSqlUtils;
import net.kiigo.core.dao.mybatis.utils.MyBatisSqlUtils.MyBatisSql;

import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionManager;
import org.apache.ibatis.session.defaults.DefaultSqlSession;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * MybatisImpl dao 层具体实现
 * 
 * @author Coollf
 * 
 */
public class FreeDaoMybatisImpl implements FreeDao {
    private String sessionName = "def_session";

    public static int BATCH_SIZE = 100;

    public FreeDaoMybatisImpl() {
    }

    public FreeDaoMybatisImpl(String sessionName) {
        this.sessionName = sessionName;
    }

    public <T> List<T> query(String sqlId, Object params, Class<T>... T) {
        SqlSession session = getSqlSession();
        List<T> result = session.selectList(sqlId, params);
        session.close();
        return result;
    }

    public <T> T queryObject(String sqlId, Object params, Class<T>... T) {
        List<T> objs = query(sqlId, params, T);
        return objs.size() > 0 ? objs.get(0) : null;
    }

    private SqlSession getSqlSession() {
        return KGApplicationContextManger.getApplicationContext().getBean(sessionName, SqlSession.class);
    }

    /***
     * 获取原生的SqlSession(需要自己回收链接)
     * 
     * @return
     */
    private SqlSession getNativeSqlSession() {
        SqlSession sqlSession = getSqlSession();
        if (sqlSession instanceof SqlSessionTemplate) {
            System.out.println("SqlSessionTemplate 拿到的连接:" + sqlSession.getConnection());
            sqlSession.close();
            return ((SqlSessionTemplate) sqlSession).getSqlSessionFactory().openSession();
        } else if (sqlSession instanceof DefaultSqlSession) {
            return sqlSession;
        } else if (sqlSession instanceof SqlSessionManager) {
            return sqlSession;
        }
        return sqlSession;
    }

    public boolean insert(String sqlId, Object params) throws Exception {
        SqlSession session = getSqlSession();
        try {
            int affected = session.insert(sqlId, params);
            session.close();
            return affected == 1;
        } finally {
            closeSession(session);
        }
    }

    public boolean modify(String sqlId, Object parameter) throws Exception {
        SqlSession session = getSqlSession();
        try {
            int affected = session.update(sqlId, parameter);
            session.close();
            return affected > 0;
        } finally {
            closeSession(session);
        }
    }

    public boolean remove(String sqlId, Object parameter) throws Exception {
        SqlSession session = getSqlSession();
        try {
            int affected = session.delete(sqlId, parameter);
            session.close();
            return affected > 0;
        } finally {
            closeSession(session);
        }
    }

    public <T> PageCollection<T> query(String sqlId, Object parameter, long offset, int pageSize, Class<T>... T) throws Exception {
        SqlSession session = getSqlSession();
        List<T> datas;
        try {
            datas = session.selectList(sqlId, parameter, new RowBounds((int) offset, pageSize));
            return new PageCollection<T>(datas, offset, pageSize).setTotalSize(PagingUtils.getQueryItemsSize());
        } finally {
            closeSession(session);
        }
    }

    @Override
    public <T> PageCollection<T> queryPage(String sqlId, Object parameter, int pageIndex, int pageSize, Class<T> T)
            throws Exception {
        int offset;
        if(pageIndex <= 1){
            offset = 0;
        }else{
            offset = (pageIndex - 1) * pageSize;
        }
        return query(sqlId, parameter, offset, pageSize, T);
    }

    @Override
    public boolean insert(String sqlId, Object... parameters) throws Exception {
        SqlSession session = getNativeSqlSession();
        Connection connection = session.getConnection();
        System.out.println("SqlSession        拿到的连接:" + connection);
        try {
            Map<String, ArrayList<Object[]>> sqlParamsMapper = new HashMap<String, ArrayList<Object[]>>();
            for (Object parameter : parameters) {
                MyBatisSql sqls = MyBatisSqlUtils.getMyBatisSql(session, sqlId, BeanUtils.transBean2Map(parameter));
                if (sqlParamsMapper.get(sqls.getSql()) == null) {
                    sqlParamsMapper.put(sqls.getSql(), new ArrayList<Object[]>());
                }
                sqlParamsMapper.get(sqls.getSql()).add(sqls.getParameters());
            }// 处理完成
            for (String sql : sqlParamsMapper.keySet()) {// 每条匹配的sql
                PreparedStatement preparedStatement = connection.prepareStatement(sql);
                int count = 0;
                for (Object[] paramObjs : sqlParamsMapper.get(sql)) {// 每条记录
                    count++;
                    for (int index = 0; index < paramObjs.length; index++) {
                        preparedStatement.setObject(index + 1, paramObjs[index]);
                    }
                    preparedStatement.addBatch();
                    if (count % BATCH_SIZE == 0) {// 默认超过100条提交一次
                        preparedStatement.executeBatch();
                        preparedStatement.clearBatch();
                    }
                }
                preparedStatement.executeBatch();
                preparedStatement.close();
            }
            return true;
        } catch (Throwable e) {
            throw new Exception(e);
        } finally {
            closeConnection(connection);
            closeSession(session);
        }
    }

    @Override
    public Object insertReadKey(String sqlId, Object parameter) throws Exception {
        long tag = System.currentTimeMillis();
        SqlSession session = getNativeSqlSession();
        System.out.println("获取[Session]连接耗时:" + (System.currentTimeMillis() - tag));
        tag = System.currentTimeMillis();
        Connection connection = session.getConnection();
        System.out.println("获取[connection]连接耗时:" + (System.currentTimeMillis() - tag));
        System.out.println("SqlSession        拿到的连接:" + connection);
        try {
            long start = System.currentTimeMillis();
            MyBatisSql sqls = MyBatisSqlUtils.getMyBatisSql(session, sqlId, BeanUtils.transBean2Map(parameter));
            Logger.getLogger("net.kiigo.core.dao.impl.FreeDaoMybatisImpl").log(Level.FINER,
                    "SQL parse elapsed time:" + (System.currentTimeMillis() - start) + " milliseconds");

            start = System.currentTimeMillis();
            PreparedStatement preparedStatement = connection.prepareStatement(sqls.getSql(),
                    PreparedStatement.RETURN_GENERATED_KEYS);
            Object[] paramObjs = sqls.getParameters();
            for (int index = 0; index < paramObjs.length; index++) {
                preparedStatement.setObject(index + 1, paramObjs[index]);
            }
            Object pk = null;
            preparedStatement.execute();
            ResultSet rs = preparedStatement.getGeneratedKeys();
            if (rs.next()) {
                pk = rs.getObject(1);
            }
            Logger.getLogger("net.kiigo.core.dao.impl.FreeDaoMybatisImpl").log(Level.FINER,
                    "SQL execute elapsed time:" + (System.currentTimeMillis() - start) + " milliseconds");
            return pk;
        } finally {
            closeConnection(connection);
            closeSession(session);
        }
    }

    private static final void closeSession(SqlSession session) {
        try {
            if (session != null)
                session.close();
        } catch (Exception e) {
            Logger.getLogger("net.kiigo.core.dao.impl.FreeDaoMybatisImpl").log(Level.WARNING, "the sqlSession close error", e);
        }
    }

    private static final void closeConnection(Connection connection) {
        try {
            if (connection != null && connection.getTransactionIsolation() == Connection.TRANSACTION_NONE)
                connection.close();
        } catch (SQLException e) {
            Logger.getLogger("net.kiigo.core.dao.impl.FreeDaoMybatisImpl").log(Level.WARNING, "the Connection close error", e);
        }
    }

}
