/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.plugin.core.memory;

import static org.junit.Assert.*;

import org.junit.*;

import ghidra.app.cmd.memory.MoveBlockListener;
import ghidra.app.cmd.memory.MoveBlockTask;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.database.ProgramBuilder;
import ghidra.program.database.ProgramDB;
import ghidra.program.database.data.DataTypeManagerDB;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.test.AbstractGhidraHeadedIntegrationTest;
import ghidra.test.TestEnv;
import ghidra.util.task.*;

/**
 * Test the model that moves a block of memory.
 * 
 * 
 */
public class MoveBlockModelTest extends AbstractGhidraHeadedIntegrationTest
		implements MoveBlockListener {
	private Program notepad;
	private Program x8051;
	private PluginTool tool;
	private TestEnv env;
	private MoveBlockModel model;
	private MemoryBlock block;
	private boolean expectedStatus;
	private boolean moveCompleted;
	private boolean status;
	private String errMsg;

	/**
	 * Constructor for MoveBlockModelTest.
	 * 
	 * @param name
	 */
	public MoveBlockModelTest() {
		super();
	}

	private Program buildProgram1(String programName) throws Exception {
		ProgramBuilder builder = new ProgramBuilder(programName, ProgramBuilder._TOY);
		builder.createMemory(".text", Long.toHexString(0x1001000), 0x6600);
		builder.createMemory(".data", Long.toHexString(0x1008000), 0x600);
		builder.createMemory(".rsrc", Long.toHexString(0x100A000), 0x5400);
		builder.createMemory(".bound_import_table", Long.toHexString(0xF0000248), 0xA8);
		builder.createMemory(".debug_data", Long.toHexString(0xF0001300), 0x1C);
		return builder.getProgram();
	}

	private Program buildProgram2(String programName) throws Exception {
		ProgramBuilder builder = new ProgramBuilder(programName, ProgramBuilder._8051);
		builder.createMemory("CODE", "CODE:0000", 0x1948);
		builder.createMemory("INTMEM", "INTMEM:00", 0x8);
		builder.createMemory("INTMEM", "INTMEM:08", 0x8);
		builder.createMemory("INTMEM", "INTMEM:10", 0x8);
		builder.createMemory("INTMEM", "INTMEM:18", 0x8);
		builder.createMemory("INTMEM", "INTMEM:20", 0xE0);
		builder.createMemory("SFR", "SFR:80", 0x80);
		builder.createMemory("BITS", "BITS:00", 0x80);
		builder.createMemory("BITS", "BITS:80", 0x80);
		return builder.getProgram();
	}

	/*
	 * @see TestCase#setUp()
	 */
	@Before
	public void setUp() throws Exception {
		env = new TestEnv();
		tool = env.getTool();
		notepad = buildProgram1("notepad");
		x8051 = buildProgram2("x08");
		block = notepad.getMemory().getBlock(getNotepadAddr(0x1001000));
		model = new MoveBlockModel(notepad);
		model.setMoveBlockListener(this);
		model.initialize(block);

		int transactionID = x8051.startTransaction("Set settings");
		DataTypeManagerDB dtm = ((ProgramDB) x8051).getDataManager();
		for (int i = 0; i < 10; i++) {
			Address a = getAddr(x8051, "BITS", i);
			dtm.setStringSettingsValue(a, "color", "red" + i);
			dtm.setLongSettingsValue(a, "someLongValue", i);
			dtm.setByteSettingsValue(a, "bytes", new byte[] { 0, 1, 2 });
		}
		x8051.endTransaction(transactionID, true);
	}

	/*
	 * @see TestCase#tearDown()
	 */
	@After
	public void tearDown() {
		env.release(x8051);
		env.release(notepad);
		env.dispose();
	}

	@Test
	public void testSetUpModel() {
		assertEquals(block.getName(), model.getName());
		assertEquals(block.getStart(), model.getStartAddress());
		assertEquals(block.getEnd(), model.getEndAddress());
		String s = model.getLengthString();
		assertTrue(s.indexOf("0x6600") > 0);
		assertEquals(block.getStart(), model.getNewStartAddress());
		assertEquals(block.getEnd(), model.getNewEndAddress());
		assertTrue(model.getMessage().length() == 0);
	}

	@Test
	public void testSetNewStart() {
		model.setNewStartAddress(getNotepadAddr(0x1000000));
		assertEquals(getNotepadAddr(0x010065ff), model.getNewEndAddress());
	}

	@Test
	public void testSetNewEnd() {
		model.setNewEndAddress(getNotepadAddr(0x1001000));
		assertEquals(getNotepadAddr(0x00ffaa01), model.getNewStartAddress());
	}

	@Test
	public void testBadEnd() {
		model.setNewEndAddress(getNotepadAddr(0x1007));
		assertTrue(model.getMessage().length() > 0);
	}

	@Test
	public void testMoveBlockStart() throws Exception {
		model.setNewStartAddress(getNotepadAddr(0x2000000));
		expectedStatus = true;
		launch(model.makeTask());
		// wait until the we get the move complete notification
		while (!moveCompleted || !notepad.canLock()) {
			Thread.sleep(1000);
		}
		assertEquals("Error message= [" + errMsg + "], ", expectedStatus, status);
	}

	@Test
	public void testMoveBlockEnd() throws Exception {
		model.setNewEndAddress(getNotepadAddr(0x2007500));
		expectedStatus = true;
		launch(model.makeTask());
		// wait until the we get the move complete notification
		while (!moveCompleted || !notepad.canLock()) {
			Thread.sleep(1000);
		}
		assertEquals("Error message= [" + errMsg + "], ", expectedStatus, status);
	}

	@Test
	public void testSetUpBitBlock() {
		Address start = getAddr(x8051, "BITS", 0);
		block = x8051.getMemory().getBlock(start);
		model = new MoveBlockModel(x8051);
		model.setMoveBlockListener(this);
		model.initialize(block);
		assertEquals(start, model.getNewStartAddress());
		assertEquals(getAddr(x8051, "BITS", 0x7f), model.getEndAddress());
	}

	@Test
	public void testMoveBitBlockOverlap() throws Exception {
		Address start = getAddr(x8051, "BITS", 0);
		block = x8051.getMemory().getBlock(start);
		model = new MoveBlockModel(x8051);
		model.setMoveBlockListener(this);
		model.initialize(block);
		start = getAddr(x8051, "INTMEM", 0x50);
		model.setNewStartAddress(start);
		assertEquals(getAddr(x8051, "INTMEM", 0xcf), model.getNewEndAddress());
		expectedStatus = false;
		launch(model.makeTask());
		// wait until the we get the move complete notification
		while (!moveCompleted || !x8051.canLock()) {
			Thread.sleep(1000);
		}
		assertEquals("Error message= [" + errMsg + "], ", expectedStatus, status);
	}

	@Test
	public void testMoveBitBlock() throws Exception {
		Address start = getAddr(x8051, "BITS", 0);
		block = x8051.getMemory().getBlock(start);
		assertNotNull(block);
		model = new MoveBlockModel(x8051);
		model.setMoveBlockListener(this);
		model.initialize(block);
		start = getAddr(x8051, "CODE", 0x2000);
		model.setNewStartAddress(start);
		expectedStatus = true;
		moveCompleted = false;
		launch(model.makeTask());
		// wait until the we get the move complete notification
		while (!moveCompleted || !x8051.canLock()) {
			Thread.sleep(1000);
		}
		// make sure settings on data got moved
		DataTypeManagerDB dtm = ((ProgramDB) x8051).getDataManager();

		for (int i = 0; i < 10; i++) {
			Address a = getAddr(x8051, "CODE", 0x2000 + i);

			String s = dtm.getStringSettingsValue(a, "color");
			assertEquals("red" + i, s);

			Long lvalue = dtm.getLongSettingsValue(a, "someLongValue");
			assertEquals(i, lvalue.longValue());

			assertNotNull(dtm.getByteSettingsValue(a, "bytes"));
		}
	}

	@Test
	public void testMoveOverlayBlock() throws Exception {
		// create an overlay block
		int transactionID = notepad.startTransaction("test");
		MemoryBlock memBlock = null;
		try {
			memBlock = notepad.getMemory().createInitializedBlock("overlay",
				getNotepadAddr(0x01001000), 0x1000, (byte) 0xa, null, true);
		}
		finally {
			notepad.endTransaction(transactionID, true);
		}
		assertNotNull(memBlock);

		model = new MoveBlockModel(notepad);
		model.setMoveBlockListener(this);
		model.initialize(memBlock);

		Address newStart = memBlock.getStart().getNewAddress(0x01002000);
		model.setNewStartAddress(newStart);

		expectedStatus = false;
		errMsg = null;

		launch(model.makeTask());
		while (!moveCompleted || !notepad.canLock()) {
			Thread.sleep(1000);
		}
		assertTrue(!expectedStatus);
		assertNotNull(errMsg);
	}

	private void launch(Task task) {
		new TaskLauncher(task, new TaskMonitorAdapter() {
			@Override
			public void setMessage(String message) {
				errMsg = message;
			}
		});
	}

	private Address getNotepadAddr(int offset) {
		return notepad.getMinAddress().getNewAddress(offset);
	}

	private Address getAddr(Program p, String spaceName, int offset) {
		AddressSpace space = p.getAddressFactory().getAddressSpace(spaceName);
		return space.getAddress(offset);
	}

	/**
	 * @see ghidra.app.plugin.contrib.memory.MoveBlockListener#moveBlockCompleted(boolean,
	 *      java.lang.String)
	 */
	@Override
	public void moveBlockCompleted(MoveBlockTask cmd) {
		moveCompleted = true;
		this.status = cmd.getStatus();
	}

	/**
	 * @see ghidra.app.plugin.contrib.memory.MoveBlockListener#stateChanged()
	 */
	@Override
	public void stateChanged() {
	}

}
