package blocktimings;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Scanner;
import org.json.JSONObject;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import javax.swing.DefaultListModel;
import javax.swing.JOptionPane;
import org.json.JSONArray;
import org.json.JSONException;

public class BlockTimings extends javax.swing.JFrame
{   
    private int startHeight;
    private int finishHeight;
    private int count;
    private int target;
    private int deviation;
    private float power;
    private boolean cancelled;
    private int blockTimeRange;
    
    private final DefaultListModel dbListModel;
    
    public BlockTimings()
    {
        initComponents();
        
        dbListModel = (DefaultListModel) dbList.getModel();
        
        FindDbFiles();
        
        tablesList.addListSelectionListener((javax.swing.event.ListSelectionEvent event) -> 
        {
            if (tablesList.getModel().getSize() > 0 && !event.getValueIsAdjusting())
            {
                if(tablesList.getSelectedValue() != null) 
                {
                    try (Connection connection = Database.getConnection(dbList.getSelectedValue()))
                    {
                        Database.fillJTable(tablesList.getSelectedValue(), resultsTable, connection); 
                    }
                    catch (NullPointerException | SQLException e)
                    {
                        System.out.println(e.toString() + " @ tablesList selectionListener");
                    }
                }
            }
        });        
    }
    
    private void FindDbFiles()
    {
         File folder = new File(System.getProperty("user.dir") + "/databases");
         if(!folder.exists())
             return;
         
        File[] listOfFiles = folder.listFiles();

        for (File file : listOfFiles)
            if (file.isFile())
                if (file.getName().endsWith(".mv.db"))
                {
                    String name = file.getName().split("\\.",2)[0];
                    dbListModel.addElement(name);
                }        
    }
    
    private int calculateTimeOffset(double keyDistanceRatio)
    {
        double transformed = Math.pow(keyDistanceRatio, power);
        int timeOffset = (int)(deviation * 2 * transformed);
        return timeOffset;
    }    
    
    private void fetchAndProcessBlocks(Connection connection, String dbName)
    {    
        String tableName =  String.format("T%d_D%d_P0_%d", target, deviation, (int)(power * 100));
        String summaryTableName = tableName + "_Summary";
        createTables(connection,tableName);
        
        statusLabel.setText(statusLabel.getText() + "created table " + tableName + " -> fetching blocks...");
        
        int timeOffset;
        int meanTimeOffset;
        int totalTimeOffset = 0;
        int timeOffsetDiff;
        int blockTime;
        int mismatchCount = 0;
        int errors = 0;
        int minterLevel;
        int onlineAccountsCount;
        double keyDistanceRatio;
        long timeDelta;
        int height = startHeight;
        finishHeight = startHeight + count - 1;
        
        int minBlockTime= 0;
        int maxBlockTime = 0;
        
//        System.out.println(String.format("Fetching blocks from height %s to %s\n\n", integerFormat(startHeight),integerFormat(finishHeight)));
        
        String jsonString;
        JSONObject jSONObject;        
        
        while(height <= finishHeight)
        {
            if(cancelled)
                break;
            
            jsonString = getJsonString(String.format("http://localhost:12391/blocks/byheight/%d/mintinginfo", height));
            
            if(jsonString == null)
            {
                height++;
                errors++;
//                System.out.println("Error fetching minting info for block " + height + "\n\n");
                continue;
            }            
            
            jSONObject = new JSONObject(jsonString);
            minterLevel = jSONObject.getInt("minterLevel");
            onlineAccountsCount = jSONObject.getInt("onlineAccountsCount");
            keyDistanceRatio = jSONObject.getDouble("keyDistanceRatio");
            timeDelta = jSONObject.getLong("timeDelta");
            
            timeOffset = calculateTimeOffset(keyDistanceRatio);
            blockTime = target - deviation + timeOffset;
            
            minBlockTime = startHeight == height ? blockTime : minBlockTime;
            minBlockTime = blockTime < minBlockTime ? blockTime : minBlockTime;
            maxBlockTime = blockTime > maxBlockTime ? blockTime : maxBlockTime;
            
            Database.insertIntoDB(new String[]{
                tableName,
                "block", String.valueOf(height),
                "minter_level", String.valueOf(minterLevel),
                "online_accounts", String.valueOf(onlineAccountsCount),
                "key_distance_ratio", String.valueOf(keyDistanceRatio),
                "time_offset", String.valueOf(timeOffset),
                "block_time_real", String.valueOf(timeDelta),
                "block_time_calc", String.valueOf(blockTime),
                "current_min",String.valueOf(minBlockTime),
                "current_max",String.valueOf(maxBlockTime)
            }, connection);
            
            
            if(timeDelta != blockTime)
            {
                mismatchCount++;
//                System.out.println("WARNING: Block time mismatch. This is to be expected when using custom settings.\n\n");
            }
            
            totalTimeOffset += blockTime;
            height++;
        }
        
        estimateBlockTimestamps(tableName,connection);
        
        int adjustedCount = count - errors;
        if(adjustedCount == 0)
            System.out.println("No blocks were retrieved");
        
        meanTimeOffset = totalTimeOffset / count;
        timeOffsetDiff = meanTimeOffset - target;
        
        int minMaxDelta = maxBlockTime - minBlockTime;
        
        Database.insertIntoDB(new String[]{
                summaryTableName,
                "total_blocks_retrieved", String.valueOf(adjustedCount),
                "total_blocks_failed", String.valueOf(errors),
                "mean_time_offset", String.valueOf(meanTimeOffset),
                "target_time_offset", String.valueOf(target),
                "difference_from_target", String.valueOf(timeOffsetDiff),
                "block_time_range",String.valueOf(blockTimeRange),
                "min_max_delta",String.valueOf(minMaxDelta)
            }, connection);
    }
    
    private double estimateKeyDistanceRatioForLevel(int level)
    {
        double exampleKeyDistance = .5;        
        return exampleKeyDistance / level;
    }
    
    private void estimateBlockTimestamps(String tableName, Connection connection)
    {
        int minBlockTime = 9999999;
        int maxBlockTime = 0;
        
        for(int level = 1; level <= 10; level++)
        {
            double exampleKeyDistanceRatio = estimateKeyDistanceRatioForLevel(level);
            int timeOffset = calculateTimeOffset(exampleKeyDistanceRatio);
            int blockTime = target - deviation + timeOffset;
            
            if(blockTime > maxBlockTime)
                maxBlockTime = blockTime;
            if(blockTime < minBlockTime)
                minBlockTime = blockTime;   
            
            Database.insertIntoDB(new String[]{tableName + "_LEVELS",
                "level",String.valueOf(level),
                "time_offset",String.valueOf(timeOffset),
                "blocktime",String.valueOf(blockTime)}, connection);
        }
        
        blockTimeRange = maxBlockTime - minBlockTime;
    } 

    private void createTables(Connection connection, String tableName)
    {        
        Database.createTable(new String[]{
            tableName,
            "block", "int",
            "minter_level", "int",
            "online_accounts", "int",
            "key_distance_ratio", "double",
            "time_offset", "int",
            "block_time_real", "int",
            "block_time_calc", "int",
            "current_min","int",
            "current_max","int"
        }, connection);
        
        Database.createTable(new String[]{
            tableName + "_summary",
            "total_blocks_retrieved", "int",
            "total_blocks_failed", "int",
            "mean_time_offset", "int",
            "target_time_offset", "int",
            "difference_from_target", "int",
            "block_time_range","int",
            "min_max_delta","int"
        }, connection);
        
        Database.createTable(new String[]{
            tableName + "_levels",
            "level", "int",
            "time_offset", "int",
            "blocktime", "int"
        }, connection);
    }
    
    
    public static String DateFormatPath(long timeMillisec)
    {
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d yyyy 'at' HH-mm-ss");
        return dateFormat.format(timeMillisec);
    }
    
  private String integerFormat(int number)
  {
      return NumberFormat.getIntegerInstance().format(number);
  }
    
private String getJsonString(String requestURL) 
{   
    try (Scanner scanner = new Scanner(new URL(requestURL).openStream(),StandardCharsets.UTF_8.toString()))
    {
        scanner.useDelimiter("\\A");
        return scanner.hasNext() ? scanner.next() : "";
    }
    catch(Exception e)
    {
//        e.printStackTrace();
        return null;
    }
}  

public int findChainHeight()
    {    
        try
        {
            String jsonString = getJsonString("http://localhost:12391/peers");
            if(jsonString == null)
                return 0;
            
            JSONArray jSONArray = new JSONArray(jsonString);
            int highest = 0;
            int current = 0;

            for (int i = 0; i < jSONArray.length(); i++)
            {
                    if (jSONArray.getJSONObject(i).has("lastHeight"))
                    {
                        current = jSONArray.getJSONObject(i).getInt("lastHeight");
                    }
                    if (current > highest)
                    {
                        highest = current;
                    }
                }

            return highest;
        }
        catch (JSONException e)
        {
            System.out.println(e.toString() + " @ findChainHeight");
        }    
        
        return 0;       
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents()
    {
        java.awt.GridBagConstraints gridBagConstraints;

        jTabbedPane1 = new javax.swing.JTabbedPane();
        benchmarkTab = new javax.swing.JScrollPane();
        jPanel1 = new javax.swing.JPanel();
        startHeightSpinner = new javax.swing.JSpinner();
        iterationsSpinner = new javax.swing.JSpinner();
        targetIncrSpinner = new javax.swing.JSpinner();
        deviationIncrSpinner = new javax.swing.JSpinner();
        statusLabel = new javax.swing.JLabel();
        jLabel2 = new javax.swing.JLabel();
        jLabel3 = new javax.swing.JLabel();
        jLabel4 = new javax.swing.JLabel();
        powerIncrSpinner = new javax.swing.JSpinner();
        jLabel5 = new javax.swing.JLabel();
        startButton = new javax.swing.JButton();
        powerSpinner = new javax.swing.JSpinner();
        jLabel6 = new javax.swing.JLabel();
        jLabel7 = new javax.swing.JLabel();
        deviationSpinner = new javax.swing.JSpinner();
        jLabel8 = new javax.swing.JLabel();
        targetSpinner = new javax.swing.JSpinner();
        jLabel9 = new javax.swing.JLabel();
        countSpinner = new javax.swing.JSpinner();
        getHeightButton = new javax.swing.JButton();
        jLabel10 = new javax.swing.JLabel();
        resultsTab = new javax.swing.JSplitPane();
        dbListScrollpane = new javax.swing.JScrollPane();
        dbList = new javax.swing.JList(new DefaultListModel());

        dbList.addListSelectionListener((javax.swing.event.ListSelectionEvent event) ->
            {
                if (dbList.getModel().getSize() > 0 && !event.getValueIsAdjusting())
                {
                    if(dbList.getSelectedValue() != null)
                    {
                        try (Connection connection = Database.getConnection(dbList.getSelectedValue()))
                        {
                            tablesList.setListData(Database.getTables(connection).toArray(new String[]{}));
                        }
                        catch (SQLException e)
                        {
                            System.out.println(e.toString() + " @ dblistSelectionListener");
                        }
                    }
                }
            });
            jSplitPane2 = new javax.swing.JSplitPane();
            jScrollPane1 = new javax.swing.JScrollPane();
            tablesList = new javax.swing.JList(new DefaultListModel());
            jScrollPane2 = new javax.swing.JScrollPane();
            resultsTable = new javax.swing.JTable();

            setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);

            jPanel1.setLayout(new java.awt.GridBagLayout());

            startHeightSpinner.setModel(new javax.swing.SpinnerNumberModel(0, null, null, 500));
            startHeightSpinner.setToolTipText("a block height, preferably within the untrimmed range, to avoid data gaps");
            startHeightSpinner.setPreferredSize(new java.awt.Dimension(84, 25));
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 0;
            gridBagConstraints.gridy = 4;
            gridBagConstraints.insets = new java.awt.Insets(10, 10, 0, 10);
            jPanel1.add(startHeightSpinner, gridBagConstraints);

            iterationsSpinner.setModel(new javax.swing.SpinnerNumberModel(10, 1, null, 1));
            iterationsSpinner.setToolTipText("every iteration creates a benchmark with the starting variables incremented with the specified figures");
            iterationsSpinner.setPreferredSize(new java.awt.Dimension(84, 25));
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 0;
            gridBagConstraints.gridy = 6;
            gridBagConstraints.gridwidth = 2;
            gridBagConstraints.insets = new java.awt.Insets(10, 10, 0, 10);
            jPanel1.add(iterationsSpinner, gridBagConstraints);

            targetIncrSpinner.setModel(new javax.swing.SpinnerNumberModel(0, null, null, 100));
            targetIncrSpinner.setToolTipText("how much to increment this value on each subsequent iteration");
            targetIncrSpinner.setPreferredSize(new java.awt.Dimension(84, 25));
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 2;
            gridBagConstraints.gridy = 6;
            gridBagConstraints.insets = new java.awt.Insets(10, 10, 0, 10);
            jPanel1.add(targetIncrSpinner, gridBagConstraints);

            deviationIncrSpinner.setModel(new javax.swing.SpinnerNumberModel(1000, null, null, 100));
            deviationIncrSpinner.setToolTipText("how much to increment this value on each subsequent iteration");
            deviationIncrSpinner.setPreferredSize(new java.awt.Dimension(84, 25));
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 3;
            gridBagConstraints.gridy = 6;
            gridBagConstraints.insets = new java.awt.Insets(10, 10, 0, 10);
            jPanel1.add(deviationIncrSpinner, gridBagConstraints);

            statusLabel.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
            statusLabel.setText("                                                                                                                                              ");
            statusLabel.setToolTipText("a block height, preferably within the untrimmed range, to avoid data gaps");
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 0;
            gridBagConstraints.gridy = 0;
            gridBagConstraints.gridwidth = 5;
            gridBagConstraints.anchor = java.awt.GridBagConstraints.PAGE_START;
            gridBagConstraints.insets = new java.awt.Insets(10, 10, 55, 10);
            jPanel1.add(statusLabel, gridBagConstraints);

            jLabel2.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
            jLabel2.setText("Iterations");
            jLabel2.setToolTipText("every iteration creates a benchmark with the starting variables incremented with the specified figures");
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 0;
            gridBagConstraints.gridy = 5;
            gridBagConstraints.gridwidth = 2;
            gridBagConstraints.insets = new java.awt.Insets(10, 10, 0, 10);
            jPanel1.add(jLabel2, gridBagConstraints);

            jLabel3.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
            jLabel3.setText("Target incr.");
            jLabel3.setToolTipText("how much to increment this value on each subsequent iteration");
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 2;
            gridBagConstraints.gridy = 5;
            gridBagConstraints.insets = new java.awt.Insets(10, 10, 0, 10);
            jPanel1.add(jLabel3, gridBagConstraints);

            jLabel4.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
            jLabel4.setText("Deviation incr.");
            jLabel4.setToolTipText("how much to increment this value on each subsequent iteration");
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 3;
            gridBagConstraints.gridy = 5;
            gridBagConstraints.insets = new java.awt.Insets(10, 10, 0, 10);
            jPanel1.add(jLabel4, gridBagConstraints);

            powerIncrSpinner.setModel(new javax.swing.SpinnerNumberModel(0.01f, null, null, 0.01f));
            powerIncrSpinner.setToolTipText("how much to increment this value on each subsequent iteration");
            powerIncrSpinner.setPreferredSize(new java.awt.Dimension(84, 25));
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 4;
            gridBagConstraints.gridy = 6;
            gridBagConstraints.insets = new java.awt.Insets(10, 10, 0, 10);
            jPanel1.add(powerIncrSpinner, gridBagConstraints);

            jLabel5.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
            jLabel5.setText("Power incr.");
            jLabel5.setToolTipText("how much to increment this value on each subsequent iteration");
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 4;
            gridBagConstraints.gridy = 5;
            gridBagConstraints.insets = new java.awt.Insets(10, 10, 0, 10);
            jPanel1.add(jLabel5, gridBagConstraints);

            startButton.setText("Start benchmark");
            startButton.addActionListener(new java.awt.event.ActionListener()
            {
                public void actionPerformed(java.awt.event.ActionEvent evt)
                {
                    startButtonActionPerformed(evt);
                }
            });
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 1;
            gridBagConstraints.gridy = 1;
            gridBagConstraints.gridwidth = 3;
            gridBagConstraints.insets = new java.awt.Insets(0, 0, 25, 0);
            jPanel1.add(startButton, gridBagConstraints);

            powerSpinner.setModel(new javax.swing.SpinnerNumberModel(0.2f, 0.01f, null, 0.01f));
            powerSpinner.setToolTipText("used when transforming key distance to a time offset. Originates from blockchain.json");
            powerSpinner.setPreferredSize(new java.awt.Dimension(84, 25));
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 4;
            gridBagConstraints.gridy = 4;
            gridBagConstraints.insets = new java.awt.Insets(10, 10, 0, 10);
            jPanel1.add(powerSpinner, gridBagConstraints);

            jLabel6.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
            jLabel6.setText("Power");
            jLabel6.setToolTipText("used when transforming key distance to a time offset. Originates from blockchain.json");
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 4;
            gridBagConstraints.gridy = 3;
            gridBagConstraints.insets = new java.awt.Insets(0, 10, 0, 10);
            jPanel1.add(jLabel6, gridBagConstraints);

            jLabel7.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
            jLabel7.setText("Deviation");
            jLabel7.setToolTipText("the allowed block time deviation in milliseconds. Originates from blockchain.json");
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 3;
            gridBagConstraints.gridy = 3;
            gridBagConstraints.insets = new java.awt.Insets(0, 10, 0, 10);
            jPanel1.add(jLabel7, gridBagConstraints);

            deviationSpinner.setModel(new javax.swing.SpinnerNumberModel(30000, 0, null, 500));
            deviationSpinner.setToolTipText("the allowed block time deviation in milliseconds. Originates from blockchain.json");
            deviationSpinner.setPreferredSize(new java.awt.Dimension(84, 25));
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 3;
            gridBagConstraints.gridy = 4;
            gridBagConstraints.insets = new java.awt.Insets(10, 10, 0, 10);
            jPanel1.add(deviationSpinner, gridBagConstraints);

            jLabel8.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
            jLabel8.setText("Target");
            jLabel8.setToolTipText("the target block time in milliseconds. Originates from blockchain.json");
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 2;
            gridBagConstraints.gridy = 3;
            gridBagConstraints.insets = new java.awt.Insets(0, 10, 0, 10);
            jPanel1.add(jLabel8, gridBagConstraints);

            targetSpinner.setModel(new javax.swing.SpinnerNumberModel(60000, 0, null, 500));
            targetSpinner.setToolTipText("the target block time in milliseconds. Originates from blockchain.json");
            targetSpinner.setPreferredSize(new java.awt.Dimension(84, 25));
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 2;
            gridBagConstraints.gridy = 4;
            gridBagConstraints.insets = new java.awt.Insets(10, 10, 0, 10);
            jPanel1.add(targetSpinner, gridBagConstraints);

            jLabel9.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
            jLabel9.setText("Count");
            jLabel9.setToolTipText("the number of blocks to request and analyse after the start height");
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 1;
            gridBagConstraints.gridy = 3;
            gridBagConstraints.insets = new java.awt.Insets(0, 10, 0, 10);
            jPanel1.add(jLabel9, gridBagConstraints);

            countSpinner.setModel(new javax.swing.SpinnerNumberModel(100, 1, 1400, 10));
            countSpinner.setToolTipText("the number of blocks to request and analyse after the start height");
            countSpinner.setPreferredSize(new java.awt.Dimension(84, 25));
            countSpinner.addChangeListener(new javax.swing.event.ChangeListener()
            {
                public void stateChanged(javax.swing.event.ChangeEvent evt)
                {
                    countSpinnerStateChanged(evt);
                }
            });
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 1;
            gridBagConstraints.gridy = 4;
            gridBagConstraints.insets = new java.awt.Insets(10, 10, 0, 10);
            jPanel1.add(countSpinner, gridBagConstraints);

            getHeightButton.setText("Get start height");
            getHeightButton.setToolTipText("Gets the start height by fetching the current chain height and substracting the count from that");
            getHeightButton.addActionListener(new java.awt.event.ActionListener()
            {
                public void actionPerformed(java.awt.event.ActionEvent evt)
                {
                    getHeightButtonActionPerformed(evt);
                }
            });
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 1;
            gridBagConstraints.gridy = 2;
            gridBagConstraints.gridwidth = 3;
            gridBagConstraints.insets = new java.awt.Insets(5, 0, 25, 0);
            jPanel1.add(getHeightButton, gridBagConstraints);

            jLabel10.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
            jLabel10.setText("Start height");
            jLabel10.setToolTipText("a block height, preferably within the untrimmed range, to avoid data gaps");
            gridBagConstraints = new java.awt.GridBagConstraints();
            gridBagConstraints.gridx = 0;
            gridBagConstraints.gridy = 3;
            gridBagConstraints.insets = new java.awt.Insets(0, 10, 0, 10);
            jPanel1.add(jLabel10, gridBagConstraints);

            benchmarkTab.setViewportView(jPanel1);

            jTabbedPane1.addTab("Benchmark", benchmarkTab);

            resultsTab.setDividerLocation(150);

            dbList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
            dbListScrollpane.setViewportView(dbList);

            resultsTab.setLeftComponent(dbListScrollpane);

            jSplitPane2.setDividerLocation(250);

            jScrollPane1.setViewportView(tablesList);

            jSplitPane2.setLeftComponent(jScrollPane1);

            resultsTable.setModel(new javax.swing.table.DefaultTableModel(
                new Object [][]
                {

                },
                new String []
                {

                }
            ));
            jScrollPane2.setViewportView(resultsTable);

            jSplitPane2.setRightComponent(jScrollPane2);

            resultsTab.setRightComponent(jSplitPane2);

            jTabbedPane1.addTab("Results", resultsTab);

            javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
            getContentPane().setLayout(layout);
            layout.setHorizontalGroup(
                layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addComponent(jTabbedPane1, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, 735, Short.MAX_VALUE)
            );
            layout.setVerticalGroup(
                layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addComponent(jTabbedPane1, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, 501, Short.MAX_VALUE)
            );

            pack();
        }// </editor-fold>//GEN-END:initComponents

    private void getHeightButtonActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_getHeightButtonActionPerformed
    {//GEN-HEADEREND:event_getHeightButtonActionPerformed
        Thread thread = new Thread(()->
        {
            int chainHeight = findChainHeight();

            if(chainHeight == 0)
            {
                JOptionPane.showMessageDialog(this,"Could not fetch chain height.\n\nIs your Qortal core running?");
                return;
            }
            startHeightSpinner.setValue(chainHeight - (int) countSpinner.getValue() - 2);
        });
        thread.start();
    }//GEN-LAST:event_getHeightButtonActionPerformed

    private void startButtonActionPerformed(java.awt.event.ActionEvent evt)//GEN-FIRST:event_startButtonActionPerformed
    {//GEN-HEADEREND:event_startButtonActionPerformed
        if(startButton.getText().equals("Cancel benchmark"))
        {
            cancelled = true;
            return;
        }        
        
        startHeight = (int) startHeightSpinner.getValue();
        count = (int) countSpinner.getValue();        
        target = (int) targetSpinner.getValue();
        deviation = (int) deviationSpinner.getValue();
        power = (float) powerSpinner.getValue();    
        
        Thread thread = new Thread(()->
        {
            try
            {
                String jsonString = getJsonString("http://localhost:12391/admin/status");
                if(jsonString == null)
                {
                    JOptionPane.showMessageDialog(this, 
                            "Aborting benchmark. Could not connect to Qortal core.\n\nIs your Qortal core online?");
                    return;
                }
                
                int chainHeight = findChainHeight();
                
                if(startHeight + count - 2 > findChainHeight())
                {
                    JOptionPane.showMessageDialog(this, "Startheight + count cannot exceed current chainheight (" + chainHeight + ")");
                    return;
                }
                
                if(chainHeight - 1400 > startHeight)
                    if(JOptionPane.showConfirmDialog(this, 
                            "Your startblock is more than 1400 blocks old.\n"
                                    + "Blocks older than 1 day are outside of the untrimmed range\n"
                                    + "Continue anyway?", "Warning", JOptionPane.WARNING_MESSAGE) == JOptionPane.CANCEL_OPTION)
                        return;
                
                startButton.setText("Cancel benchmark");
                
                String dbName = DateFormatPath(System.currentTimeMillis());                
                Connection connection = Database.getConnection(dbName); //creates the database
                dbListModel.addElement(dbName);
                int itertations = (int)  iterationsSpinner.getValue();
                for(int i = 0;i < itertations;i++)
                {    
                    if(cancelled)
                        break;
                    
                    statusLabel.setText("Iteration " + (i + 1) + " out of " + itertations + " started -> " );
                    fetchAndProcessBlocks(connection, dbName);
                    
                    target += (int)targetIncrSpinner.getValue();
                    deviation += (int)deviationIncrSpinner.getValue();
                    power += (float)powerIncrSpinner.getValue();
                }   
                
                statusLabel.setText("Benchmark completed, filename is '" + dbName + "'");
                startButton.setText("Start benchmark");
                cancelled = false;
            }
            catch (JSONException e)
            {
                System.out.println(e.toString() + " @ startButtonAP");
            }    
        });
        thread.start();
    }//GEN-LAST:event_startButtonActionPerformed

    private void countSpinnerStateChanged(javax.swing.event.ChangeEvent evt)//GEN-FIRST:event_countSpinnerStateChanged
    {//GEN-HEADEREND:event_countSpinnerStateChanged
        //when changing the count we must be certain that the startheight + count does not exceed chainheight
        getHeightButtonActionPerformed(null);
    }//GEN-LAST:event_countSpinnerStateChanged

    public static void main(String args[])
    {
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        try
        {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels())
            {
                if ("Nimbus".equals(info.getName()))
                {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        }
        catch (ClassNotFoundException | InstantiationException | IllegalAccessException | javax.swing.UnsupportedLookAndFeelException ex)
        {
            java.util.logging.Logger.getLogger(BlockTimings.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>
        //</editor-fold>

        java.awt.EventQueue.invokeLater(() ->
        {
            new BlockTimings().setVisible(true);
            
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JScrollPane benchmarkTab;
    private javax.swing.JSpinner countSpinner;
    private javax.swing.JList<String> dbList;
    private javax.swing.JScrollPane dbListScrollpane;
    private javax.swing.JSpinner deviationIncrSpinner;
    private javax.swing.JSpinner deviationSpinner;
    private javax.swing.JButton getHeightButton;
    private javax.swing.JSpinner iterationsSpinner;
    private javax.swing.JLabel jLabel10;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel8;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JScrollPane jScrollPane2;
    private javax.swing.JSplitPane jSplitPane2;
    private javax.swing.JTabbedPane jTabbedPane1;
    private javax.swing.JSpinner powerIncrSpinner;
    private javax.swing.JSpinner powerSpinner;
    private javax.swing.JSplitPane resultsTab;
    private javax.swing.JTable resultsTable;
    private javax.swing.JButton startButton;
    private javax.swing.JSpinner startHeightSpinner;
    private javax.swing.JLabel statusLabel;
    private javax.swing.JList<String> tablesList;
    private javax.swing.JSpinner targetIncrSpinner;
    private javax.swing.JSpinner targetSpinner;
    // End of variables declaration//GEN-END:variables
}
