package mars.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.*;

	
/*
Copyright (c) 2020, Nick Dimitrakopoulos

This tool was developed by Nick Dimitrakopoulos

Permission is hereby granted, free of charge, to any person obtaining 
a copy of this software and associated documentation files (the 
"Software"), to deal in the Software without restriction, including 
without limitation the rights to use, copy, modify, merge, publish, 
distribute, sublicense, and/or sell copies of the Software, and to 
permit persons to whom the Software is furnished to do so, subject 
to the following conditions:

The above copyright notice and this permission notice shall be 
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF 
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. 
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR 
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF 
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION 
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/**
 * A MARS tool to analyze the MIPS datapath during the execution of an assembly program.
 *
 * @author <a href="mailto:nikosdelta@protonmail.com">Nick Dimitrakopoulos</a>
 */
public class DatapathAnalyzer extends AbstractMarsToolAndApplication {

    private static final String heading = "Datapath Analyzer";
    private static final String version = " Version 1.0 (Nick Dimitrakopoulos)";

    private int lastAddress = -1;
    private List<Signal> signals;

    private final List<InstructionInfo> instructionInfoList = new ArrayList<>();


    private final JTextArea message = new JTextArea();
    private final JButton saveCSVButton = new JButton();

    /**
     * Simple constructor, likely used by the MARS Tools menu mechanism
     */
    public DatapathAnalyzer() {
        super(heading + ", " + version, heading);
    }


    /**
     * Required method to return Tool name.
     *
     * @return Tool name.  MARS will display this in menu item.
     */
    public String getName() {
        return "Datapath Analyzer";
    }

    /**
     * Implementation of the inherited abstract method to build the main
     * display area of the GUI.  It will be placed in the CENTER area of a
     * BorderLayout.  The title is in the NORTH area, and the controls are
     * in the SOUTH area.
     */
    protected JComponent buildMainDisplayArea() {

        JTabbedPane pane = new JTabbedPane();
        pane.addTab("Console", makeConsoleTab());
        return pane;
    }

    private JPanel makeConsoleTab() {
        JPanel mainPanel = new JPanel(new GridLayout());

        message.setEditable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setFont(new Font("Ariel", Font.PLAIN, 12));
        message.setText("Execute instructions and let me analyze their datapath!\n" +
                "WARNING: Jump instructions are normally not supported in the MARS X-Ray datapath" +
                " but here a new Jump signal has been added.");
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
                    makeCSVFile(file);
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

    private void makeCSVFile(File file) throws IOException {

        FileWriter csvWriter = new FileWriter(file);

        csvWriter.append("Instruction Type").append(',')
                .append("Source").append(',')
                .append("Basic").append(',')
                .append("Read Register 1").append(',')
                .append("Read Register 2").append(',')
                .append("Write Register").append(',')
                .append("RegDst").append(',')
                .append("Branch").append(',')
                .append("MemRead").append(',')
                .append("MemtoReg").append(',')
                .append("ALUOp0").append(',')
                .append("ALUOp1").append(',')
                .append("MemWrite").append(',')
                .append("ALUSrc").append(',')
                .append("RegWrite")
                .append('\n');

        for(InstructionInfo instr: instructionInfoList)
            csvWriter.append(instr.toCSVString()).append('\n');

        csvWriter.flush();
        csvWriter.close();
    }

    private void loadSignalJSONFile()
    {
        try
        {
            ObjectMapper mapper = new ObjectMapper();
            String signalFilePath = getClass().getResource("/signals.json").toString();
            this.signals = mapper.readValue(new URL(signalFilePath), new TypeReference<>() {});
        } catch (IOException e)
        {
            e.printStackTrace();
        }
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
            BasicInstructionFormat format = instr.getInstructionFormat();
            String instructionCode = stmt.getMachineStatement();

            //SPECIAL HANDLING FOR LOAD/STORE INSTRUCTIONS
            //( MARS IMPLEMENTATION DOESN'T HAVE A BasicInstructionFormat FOR THEM )
            if(instructionCode.substring(0, 6).matches("100[0-1][0-1][0-1]")) //LOAD TYPE
            {
                ITypeInstructionInfo iInstruction =  new ITypeInstructionInfo(InstructionInfo.LOAD_TYPE,stmt.getSource(),
                        stmt.getPrintableBasicAssemblyStatement(),instructionCode);
                for(Signal signal : signals)
                    iInstruction.putSignal(signal.getName(),signal.getLoad());
                instructionInfoList.add(iInstruction);
            }
            else if(instructionCode.substring(0, 6).matches("101[0-1][0-1][0-1]")) //STORE TYPE
            {
                ITypeInstructionInfo iInstruction =  new ITypeInstructionInfo(InstructionInfo.STORE_TYPE,stmt.getSource(),
                        stmt.getPrintableBasicAssemblyStatement(),instructionCode);
                for(Signal signal : signals)
                    iInstruction.putSignal(signal.getName(),signal.getStore());
                instructionInfoList.add(iInstruction);
            }
            else if (format == BasicInstructionFormat.R_FORMAT)
            {
                RTypeInstructionInfo rInstruction =  new RTypeInstructionInfo(stmt.getSource(),
                        stmt.getPrintableBasicAssemblyStatement(), instructionCode);
                for(Signal signal : signals)
                    rInstruction.putSignal(signal.getName(),signal.getRType());
                instructionInfoList.add(rInstruction);
            }
            else if (format == BasicInstructionFormat.I_FORMAT)
            {
                ITypeInstructionInfo iInstruction =  new ITypeInstructionInfo(InstructionInfo.I_TYPE,stmt.getSource(),
                        stmt.getPrintableBasicAssemblyStatement(), instructionCode);
                for(Signal signal : signals)
                    iInstruction.putSignal(signal.getName(),signal.getIType());
                instructionInfoList.add(iInstruction);
            }
            else if(format == BasicInstructionFormat.I_BRANCH_FORMAT)
            {
                ITypeInstructionInfo iInstruction =  new ITypeInstructionInfo(InstructionInfo.BRANCH_TYPE,stmt.getSource(),
                        stmt.getPrintableBasicAssemblyStatement(),instructionCode);
                for(Signal signal : signals)
                    iInstruction.putSignal(signal.getName(),signal.getBranch());
                instructionInfoList.add(iInstruction);
            }
            else if (format == BasicInstructionFormat.J_FORMAT)
            {
                JTypeInstructionInfo jInstruction =  new JTypeInstructionInfo(stmt.getSource(),
                        stmt.getPrintableBasicAssemblyStatement(),instructionCode);
                for(Signal signal : signals)
                    jInstruction.putSignal(signal.getName(),signal.getJType());
                instructionInfoList.add(jInstruction);
            }

        } catch (AddressErrorException e) {
            e.printStackTrace();
        }
        updateDisplay();
    }

    @Override
    protected void initializePreGUI() {
        lastAddress = -1;
        loadSignalJSONFile();
    }

    @Override
    protected void reset() {
        lastAddress = -1;
        instructionInfoList.clear();
        updateDisplay();
    }

    @Override
    protected void updateDisplay() {
        message.setText(generateText());
    }

    private String generateText() {
        StringBuilder builder = new StringBuilder();
        for(InstructionInfo instr : instructionInfoList)
            builder.append(instr.toString()).append('\n');
        return builder.toString();
    }

    private static class Signal
    {
        @JsonProperty private String name;
        @JsonProperty private String RType;
        @JsonProperty private String IType;
        @JsonProperty private String JType;
        @JsonProperty private String Branch;
        @JsonProperty private String Load;
        @JsonProperty private String Store;

        public Signal(){}

        public String getName()
        {
            return name;
        }

        public String getRType()
        {
            return RType;
        }

        public String getIType()
        {
            return IType;
        }

        public String getJType()
        {
            return JType;
        }

        public String getBranch()
        {
            return Branch;
        }

        public String getLoad()
        {
            return Load;
        }

        public String getStore()
        {
            return Store;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Signal signal = (Signal) o;
            return name.equals(signal.name);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(name);
        }
    }

    static abstract class InstructionInfo
    {
        public static String R_TYPE = "R-type instruction";
        public static String I_TYPE = "I-type instruction";
        public static String LOAD_TYPE = "I-type LOAD instruction";
        public static String STORE_TYPE = "I-type STORE instruction";
        public static String BRANCH_TYPE = "I-type BRANCH instruction";
        public static String J_TYPE = "J-type instruction";


        final String instructionType;
        final String instructionSource;
        final String instructionBasic;

        final String instructionCode;
        final String opcode;

        final String regRead1;
        final String regRead2;
        final String regWrite;

        public final Map<String,String> controlUnitSignals = new LinkedHashMap<>();

        public InstructionInfo(String instructionType, String instructionSource,
                               String instructionBasic, String instructionCode,
                               String regRead1, String regRead2, String regWrite)
        {
            this.instructionType = instructionType;
            this.instructionSource = instructionSource;
            this.instructionBasic = instructionBasic;
            this.instructionCode = instructionCode;
            this.opcode = instructionCode.substring(0,6);

            this.regRead1 = regRead1;
            this.regRead2 = regRead2;
            this.regWrite = regWrite;
        }

        public void putSignal(String signalName, String value)
        {
            controlUnitSignals.put(signalName,value);
        }

        public abstract String printInstructionAnalysisInfo();

        public String toCSVString()
        {
            StringBuilder builder = new StringBuilder();
            builder.append(instructionType).append(',');
            builder.append('"').append(instructionSource).append('"').append(',');
            builder.append('"').append(instructionBasic).append('"').append(',');
            builder.append('"').append(regRead1).append('"').append(',');
            builder.append('"').append(regRead2).append('"').append(',');
            builder.append('"').append(regWrite).append('"').append(',');
            builder.append(controlUnitSignals.get("RegDst")).append(',');
            builder.append(controlUnitSignals.get("Branch")).append(',');
            builder.append(controlUnitSignals.get("MemRead")).append(',');
            builder.append(controlUnitSignals.get("MemtoReg")).append(',');
            builder.append(controlUnitSignals.get("ALUOp0")).append(',');
            builder.append(controlUnitSignals.get("ALUOp1")).append(',');
            builder.append(controlUnitSignals.get("MemWrite")).append(',');
            builder.append(controlUnitSignals.get("ALUSrc")).append(',');
            builder.append(controlUnitSignals.get("RegWrite"));

            return builder.toString();
        }

        @Override
        public String toString()
        {
            StringBuilder builder = new StringBuilder();
            builder.append(instructionType).append('\n');
            builder.append("-------Basic Instruction Info-------").append('\n');
            builder.append("Source: ").append(instructionSource).append('\n');
            builder.append("Compiled assembly: ").append(instructionBasic).append('\n');
            builder.append("Instruction code: ").append(instructionCode).append('\n');
            builder.append(this.printInstructionAnalysisInfo()).append('\n');
            builder.append("-------Register File analytics-------").append('\n');
            builder.append("RegRead 1: ").append(regRead1).append('\n');
            builder.append("RegRead 2: ").append(regRead2).append('\n');
            builder.append("RegWrite: ").append(regWrite).append('\n');
            builder.append("-------Control Unit Signals-------").append('\n');
            for(Map.Entry<String,String> signal : controlUnitSignals.entrySet())
                builder.append(signal.getKey()).append(": ").append(signal.getValue()).append('\n');

            return builder.toString();
        }
    }

    static class RTypeInstructionInfo extends InstructionInfo
    {
        private final String rs;
        private final String rt;
        private final String rd;
        private final String shamt;
        private final String funct;

        public RTypeInstructionInfo(String instructionSource, String instructionBasic, String instructionCode)
        {
            super(
                    R_TYPE,
                    instructionSource,
                    instructionBasic,
                    instructionCode,
                    instructionCode.substring(6,11),
                    instructionCode.substring(11,16),
                    instructionCode.substring(16,21)
            );

            this.rs = this.regRead1;
            this.rt = this.regRead2;
            this.rd = this.regWrite;

            this.shamt = instructionCode.substring(21,26);
            this.funct = instructionCode.substring(26,32);
        }

        public String getShamt()
        {
            return shamt;
        }

        public String getFunct()
        {
            return funct;
        }

        @Override
        public String printInstructionAnalysisInfo()
        {
            StringBuilder builder = new StringBuilder();
            builder.append("-------R-Type Instruction Analysis-------").append('\n');
            builder.append("opcode: ").append(opcode).append('\n');
            builder.append("rs: ").append(rs).append('\n');
            builder.append("rt: ").append(rt).append('\n');
            builder.append("rd: ").append(rd).append('\n');
            builder.append("shamt: ").append(shamt).append('\n');
            builder.append("funct: ").append(funct);
            return builder.toString();
        }
    }

    static class ITypeInstructionInfo extends InstructionInfo
    {
        private final String rs;
        private final String rt;
        private final String immediate;

        public ITypeInstructionInfo(String instructionType,String instructionSource,
                                    String instructionBasic, String instructionCode)
        {
            super(
                    instructionType,
                    instructionSource,
                    instructionBasic,
                    instructionCode,
                    instructionCode.substring(6,11),
                    instructionCode.substring(11,16),
                    (instructionType == BRANCH_TYPE || instructionType == STORE_TYPE)
                            ? "XXXXX" : instructionCode.substring(11,16));

            this.rs = this.regRead1;
            this.rt = this.regRead2;
            this.immediate = instructionCode.substring(16,32);
        }

        @Override
        public String printInstructionAnalysisInfo()
        {
            StringBuilder builder = new StringBuilder();
            builder.append("-------I-Type Instruction Analysis-------").append('\n');
            builder.append("opcode: ").append(opcode).append('\n');
            builder.append("rs: ").append(rs).append('\n');
            builder.append("rt: ").append(rt).append('\n');
            builder.append("Immediate: ").append(immediate);
            return builder.toString();
        }
    }

    static class JTypeInstructionInfo extends InstructionInfo
    {
        private final String address;

        public JTypeInstructionInfo(String instructionSource, String instructionBasic, String instructionCode)
        {
            super(
                    InstructionInfo.J_TYPE,
                    instructionSource,
                    instructionBasic,
                    instructionCode,
                    instructionCode.substring(6,11),
                    instructionCode.substring(11,16),
                    "XXXXX");

            this.address = instructionCode.substring(6,32);
        }

        @Override
        public String printInstructionAnalysisInfo()
        {
            StringBuilder builder = new StringBuilder();
            builder.append("-------J-Type Instruction Analysis-------").append('\n');
            builder.append("opcode: ").append(opcode).append('\n');
            builder.append("address: ").append(address);
            return builder.toString();
        }
    }


}