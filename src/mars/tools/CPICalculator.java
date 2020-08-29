package mars.tools;

import mars.Globals;
import mars.ProgramStatement;
import mars.mips.hardware.AccessNotice;
import mars.mips.hardware.AddressErrorException;
import mars.mips.hardware.Memory;
import mars.mips.hardware.MemoryAccessNotice;
import mars.mips.instructions.BasicInstruction;
import mars.mips.instructions.BasicInstructionFormat;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * A MARS tool to calculate the CPI of a MIPS program.
 *
 * @author <a href="mailto:nikosdelta@protonmail.com">Nick Dimitrakopoulos</a>
 */
public class CPICalculator extends AbstractMarsToolAndApplication {

    private static final String heading = "CPI Calculator";
    private static final String version = " Version 1.1 (Nick Dimitrakopoulos)";

    private int lastAddress = -1;

    private int instrCounter = 0;
    private int counterR = 0;
    private int counterI = 0;
    private int counterJ = 0;

    private final Map<String, AtomicInteger> instructionMetrics = new HashMap<>();

    private final JTextArea message = new JTextArea();
    private final JButton saveCSVButton = new JButton();

    private Map<String, Double> instructionCPIs;
    private final JComboBox<String> instrComboBox = new JComboBox(Globals.getInstructionMnemonics().toArray());
    private final JTextField CPITextField = new JTextField("1");
    private final JButton changeCPIbutton = new JButton("Change CPI");

    private final Set<String> limitedInstructions = new HashSet<>();
    private final JTextField instrArgsField = new JTextField();
    private final JButton limitInstrButton = new JButton("Confirm");
    private final JButton resetInstrLimitsButton = new JButton("Reset");

    /**
     * Simple constructor, likely used by the MARS Tools menu mechanism
     */
    public CPICalculator() {
        super(heading + ", " + version, heading);
    }


    /**
     * Required method to return Tool name.
     *
     * @return Tool name.  MARS will display this in menu item.
     */
    public String getName() {
        return "CPI Calculator";
    }

    /**
     * Implementation of the inherited abstract method to build the main
     * display area of the GUI.  It will be placed in the CENTER area of a
     * BorderLayout.  The title is in the NORTH area, and the controls are
     * in the SOUTH area.
     */
    protected JComponent buildMainDisplayArea() {

        JTabbedPane pane = new JTabbedPane();
        pane.addTab("Console", makeOutputTab());
        pane.addTab("Preferences", makePreferencesTab());

        return pane;
    }

    private JPanel makeOutputTab() {
        JPanel mainPanel = new JPanel(new GridLayout());

        message.setEditable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setFont(new Font("Ariel", Font.PLAIN, 12));
        message.setText("Execute instructions and let me count them!");
        message.setCaretPosition(0); // Assure first line is visible and at top of scroll pane.


        saveCSVButton.setText("Export CSV File");
        saveCSVButton.addActionListener((event) -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("CSV file (.csv)","csv"));
            int option = chooser.showSaveDialog(mainPanel);
            if(option == JFileChooser.APPROVE_OPTION) {
                try {
                    File file = chooser.getSelectedFile();
                    if(!file.getName().contains(".csv"))
                        file = new File(file.toString() + ".csv");

                    InstructionStatsCalculator stats = new InstructionStatsCalculator();
                    makeCSVFile(file,stats,",");
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(mainPanel,
                            "IO Error occured. Detailed message is:" + ex.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        mainPanel.add(new JScrollPane(message));
        mainPanel.add(saveCSVButton);

        return mainPanel;

    }

    private JPanel makePreferencesTab() {

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.PAGE_AXIS));

        mainPanel.add(makeCPIpanel());
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        mainPanel.add(makeCertainInstructionsPanel());

        return mainPanel;
    }

    private JPanel makeCPIpanel() {
        JPanel mainPanel = new JPanel(new GridLayout());

        JPanel labelPanel = new JPanel();
        labelPanel.setLayout(new BoxLayout(labelPanel, BoxLayout.PAGE_AXIS));
        labelPanel.add(new JLabel("Alter CPI:"));
        labelPanel.add(new JLabel("(All instructions have a default CPI of 1)"));

        JPanel controlsPanel = new JPanel(new GridLayout());

        //EVENT LISTENERS FOR THE CPI CHANGE CONTROLS
        instrComboBox.addItemListener((event) -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                String instr = (String) event.getItem();
                CPITextField.setText(instructionCPIs.get(instr).toString());
            }
        });

        changeCPIbutton.addActionListener((event) ->
                instructionCPIs.put((String) instrComboBox.getSelectedItem(), Double.parseDouble(CPITextField.getText())));

        CPITextField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent eve) {
                String allowedChars = "0123456789.";
                char enter = eve.getKeyChar();
                if (!allowedChars.contains(String.valueOf(enter))) {
                    eve.consume();
                }
            }
        });


        controlsPanel.add(instrComboBox);
        controlsPanel.add(CPITextField);
        controlsPanel.add(changeCPIbutton);

        mainPanel.add(labelPanel);
        mainPanel.add(controlsPanel);

        return mainPanel;
    }

    private JPanel makeCertainInstructionsPanel() {
        JPanel mainPanel = new JPanel(new GridLayout());

        JPanel labelPanel = new JPanel();
        labelPanel.setLayout(new BoxLayout(labelPanel, BoxLayout.PAGE_AXIS));
        labelPanel.add(new JLabel("Count only certain instructions:"));
        labelPanel.add(new JLabel("(Pass it like {add,sub,jal})"));
        labelPanel.add(new JLabel("WARNING: Instruction stats will be altered."));

        JPanel controlsPanel = new JPanel(new GridLayout());

        limitInstrButton.addActionListener((event) -> {
            List<String> instructions = parseInstructions(instrArgsField.getText());
            for(String instr : instructions)
            {
                if(!Globals.getInstructionMnemonics().contains(instr))
                {
                    JOptionPane.showMessageDialog(controlsPanel,
                            "Invalid instruction: " + instr + ". Operation aborted.",
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }

            limitedInstructions.addAll(instructions);

            JOptionPane.showMessageDialog(controlsPanel,
                    "Only instructions " + limitedInstructions.toString() + " will be recorded from now on.",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE);

        });

        resetInstrLimitsButton.addActionListener((event) -> {
            limitedInstructions.clear();
            JOptionPane.showMessageDialog(controlsPanel,
                    "All instructions will be recorded from now on.",
                    "Info",
                    JOptionPane.INFORMATION_MESSAGE);
        });

        controlsPanel.add(instrArgsField);
        controlsPanel.add(limitInstrButton);
        controlsPanel.add(resetInstrLimitsButton);

        mainPanel.add(labelPanel);
        mainPanel.add(controlsPanel);

        return mainPanel;
    }

    private List<String> parseInstructions(String str)
    {
        return Arrays.asList(str.split(","));
    }

    private void makeCSVFile(File file , InstructionStatsCalculator stats, String delimiter) throws IOException {

        DecimalFormat dec = new DecimalFormat("#0.000");

        FileWriter csvWriter = new FileWriter(file);
        csvWriter.append("Instruction Type").append(delimiter);
        csvWriter.append("CPI").append(delimiter);
        csvWriter.append("Frequency").append(delimiter);
        csvWriter.append("CPI * Frequency").append(delimiter);
        csvWriter.append("Usage Percentage");
        csvWriter.append("\n");

        for (InstructionStatNode instrNode : stats.instructions) {
            csvWriter.append(instrNode.mnemonic).append(delimiter);
            csvWriter.append(instrNode.CPI.toString()).append(delimiter);
            csvWriter.append(instrNode.frequency.toString()).append(delimiter);
            csvWriter.append(dec.format(instrNode.totalClockCycles)).append(delimiter);
            csvWriter.append(dec.format(instrNode.usagePercentage * 100)).append("%");
            csvWriter.append("\n");
        }

        csvWriter.flush();
        csvWriter.close();
    }

    @Override
    protected void addAsObserver() {
        addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
    }

    @Override
    protected void processMIPSUpdate(Observable resource, AccessNotice notice) {
        if (!notice.accessIsFromMIPS()) return;
        if (notice.getAccessType() != AccessNotice.READ) return;
        MemoryAccessNotice m = (MemoryAccessNotice) notice;
        int a = m.getAddress();
        if (a == lastAddress) return;
        lastAddress = a;
        try {

            ProgramStatement stmt = Memory.getInstance().getStatement(a);
            if (stmt == null) return; //If memory returns an empty statement then finish execution
            BasicInstruction instr = (BasicInstruction) stmt.getInstruction();

            String instrName = instr.getName();

            if(limitedInstructions.size() > 0 && !limitedInstructions.contains(instrName)) return; //limiter

            instrCounter++;
            instructionMetrics.putIfAbsent(instrName, new AtomicInteger(0));
            instructionMetrics.get(instrName).incrementAndGet();

            BasicInstructionFormat format = instr.getInstructionFormat();
            if (format == BasicInstructionFormat.R_FORMAT)
                counterR++;
            else if (format == BasicInstructionFormat.I_FORMAT || format == BasicInstructionFormat.I_BRANCH_FORMAT)
                counterI++;
            else if (format == BasicInstructionFormat.J_FORMAT)
                counterJ++;

        } catch (AddressErrorException e) {
            e.printStackTrace();
        }
        updateDisplay();
    }

    @Override
    protected void initializePreGUI() {
        lastAddress = -1;
        instrCounter = 0;
        counterR = counterI = counterJ = 0;
        instructionMetrics.clear();
        instructionCPIs = Globals.getInstructionMnemonics().stream().collect(Collectors.toMap(Function.identity(), x -> 1D));
        limitedInstructions.clear();
    }

    @Override
    protected void reset() {
        lastAddress = -1;
        instrCounter = 0;
        counterR = counterI = counterJ = 0;
        instructionMetrics.clear();
        instructionCPIs = Globals.getInstructionMnemonics().stream().collect(Collectors.toMap(Function.identity(), x -> 1D));
        limitedInstructions.clear();

        updateDisplay();
    }

    @Override
    protected void updateDisplay() {
        message.setText(generateText());
    }

    private String generateText() {
        StringBuilder builder = new StringBuilder();
        builder.append("Total instructions executed: ").append(instrCounter).append('\n');
        builder.append("R-type instructions executed: ").append(counterR).append('\n');
        builder.append("I-type instructions executed: ").append(counterI).append('\n');
        builder.append("J-type instructions executed: ").append(counterJ).append('\n');
        builder.append("Metrics by instruction:").append('\n');
        builder.append(instructionMetrics.toString());
        return builder.toString();
    }

    class InstructionStatNode
    {
        final String mnemonic;
        final Integer frequency;
        final Double CPI;
        final Double totalClockCycles;
        Double usagePercentage;

        public InstructionStatNode(String mnemonic, Integer frequency, Double CPI) {
            this.mnemonic = mnemonic;
            this.frequency = frequency;
            this.CPI = CPI;
            this.totalClockCycles = this.frequency * this.CPI;
        }
    }

    class InstructionStatsCalculator
    {

        final List<InstructionStatNode> instructions;
        final Double totalCycles;

        public InstructionStatsCalculator() {

            this.instructions = instructionMetrics.entrySet().stream().map(x ->
                    new InstructionStatNode(
                    x.getKey(),
                    x.getValue().get(),
                    instructionCPIs.get(x.getKey())
            )).collect(Collectors.toList());

            this.totalCycles = instructions.stream().mapToDouble(x -> x.totalClockCycles).sum();
            this.instructions.forEach(x -> x.usagePercentage = x.totalClockCycles/this.totalCycles);
        }

    }


}