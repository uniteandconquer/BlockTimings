package blocktimings;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Vector;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

public class Database 
{    
    private static final String DB_FOLDER_H2 = "jdbc:h2:./databases/";
    
    public static void CreateDatabase(String database)
    {
        try 
        {
            Class.forName("org.h2.Driver");            
            Connection c = DriverManager.getConnection(DB_FOLDER_H2 + database + ";","block_timings","");   
            c.close();
        } 
        catch (ClassNotFoundException | SQLException e) 
        {
            e.printStackTrace();
        }
    }  
    
    public static Connection getConnection(String database) throws NullPointerException
    {
        try 
        {
            Class.forName("org.h2.Driver");
            Connection cn = DriverManager.getConnection(DB_FOLDER_H2 + database +
                    ";TRACE_LEVEL_FILE=0;TRACE_LEVEL_SYSTEM_OUT=0","block_timings","");
            return cn;     
        } 
        catch (ClassNotFoundException | SQLException e) 
        {
            e.printStackTrace();
            throw new NullPointerException();
        }
    }    
    
    //args[0] = table name, args[1..,3..,5..etc] = keys, args[2..,4..,6..etc] = type
    public static void createTable(String[] args, Connection c)
    {
        String sqlString = "create table if not exists " + args[0];            
        if(args.length > 1)
        {
            sqlString += " (";                
            for(int i = 1; i < args.length - 1; i++)
                sqlString += i % 2 == 1 ? args[i] + " " : args[i] + ",";                
            sqlString += args[args.length - 1] + ")";                
            executeUpdate(sqlString,c);
        }         
    }
    
    public static void executeUpdate(String statementString, Connection c)
    {
        try
        { 
            Statement stmt = c.createStatement();
            stmt.executeUpdate(statementString);
            c.commit();    
        }
        catch (SQLException e)
        {
            e.printStackTrace();
        }     
    }
    
    //args[0] = table name, args[1..,3..,5..etc] = keys, values[2..,4..,6..etc] = value
    public static void insertIntoDB(String[] args,Connection c)
    {  
        String  sqlString = "insert into " + args[0];
                 
        sqlString += " (";
        for(int i = 1; i < args.length; i+=2)
            sqlString += i + 2 == args.length ? args[i] + ") values (" : args[i] + ",";
        for(int i = 2; i < args.length; i+=2)
            sqlString += i == args.length - 1 ? args[i] + ")" : args[i] + ",";   

         executeUpdate(sqlString,c);                
    }
    
      public static ArrayList<String> getTables(Connection c)
    {
        try 
        {      
            ArrayList tables = new ArrayList<String>();
            String sqlString = "show tables";
            Statement stmt = c.createStatement();
            ResultSet resultSet = stmt.executeQuery(sqlString);
            while (resultSet.next())
                tables.add(resultSet.getString(1));
            
            return tables;
        } 
        catch (SQLException e) 
        {
            e.printStackTrace();
        }
        
        return null;        
    }
    
     public static void fillJTable(String table,JTable jTable, Connection c)
    {
        try 
        {      
            String query = String.format("select * from %s", table);
            Statement stmt = c.createStatement();
            ResultSet resultSet = stmt.executeQuery(query);    
            jTable.setModel(buildTableModel(table,resultSet));
        } 
        catch (SQLException e) 
        {
            e.printStackTrace();
        }        
    }   
     
     public static DefaultTableModel buildTableModel(String table,ResultSet rs)
    {
        try
        {            
            ResultSetMetaData metaData = rs.getMetaData();

            // names of columns
            @SuppressWarnings("UseOfObsoleteCollectionType")
            Vector<String> columnNames = new Vector<>();
            int columnCount = metaData.getColumnCount();
            for (int column = 1; column <= columnCount; column++)
            {                
                columnNames.add(metaData.getColumnName(column));
            }

            // data of the table
            @SuppressWarnings("UseOfObsoleteCollectionType")
            Vector<Vector<Object>> data = new Vector<>();
            while (rs.next())
            {
                @SuppressWarnings("UseOfObsoleteCollectionType")
                Vector<Object> vector = new Vector<>();
                for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++)
                {
                    vector.add(rs.getObject(columnIndex));
                }
                data.add(vector);
            }

            return new DefaultTableModel(data, columnNames);
        }
        catch (SQLException e)
        {
            e.printStackTrace();
        }
        
        return  null;
    }
    
     //Gets the value type of the column in a table, the header for the columns so to speak
    public ArrayList<String> getColumnHeaders(String table, Connection c)
    {
        try 
        {        
            ArrayList items = new ArrayList<String>();           
            String sqlString = "show columns from " + table;
            Statement stmt = c.createStatement();
            ResultSet resultSet = stmt.executeQuery(sqlString);
            while(resultSet.next())
                items.add(resultSet.getString(1));

            return items;
        } 
        catch (SQLException e) 
        {
            e.printStackTrace();
        }        
        return null;        
    }
     
}
