package comp0012.main;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Stack;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.apache.bcel.util.InstructionFinder;


public class ConstantFolder
{
	ClassParser parser = null;
	ClassGen gen = null;

	JavaClass original = null;
	JavaClass optimized = null;

	public ConstantFolder(String classFilePath)
	{
		try{
			this.parser = new ClassParser(classFilePath);
			this.original = this.parser.parse();
			this.gen = new ClassGen(this.original);
		} catch(IOException e){
			e.printStackTrace();
		}
	}
	//deletes while removing any goto reference to it
	private void deleteInstruction(InstructionHandle handle, InstructionList instructionList)
	{
		try {
			instructionList.delete(handle);
		} catch (TargetLostException e) {
			InstructionHandle[] targets = e.getTargets();

			for (InstructionHandle target : targets) {
				InstructionTargeter[] targeters = target.getTargeters();
				for (InstructionTargeter targeter : targeters)
					targeter.updateTarget(target, null);
			}
		}
	}

	public ArrayList<Integer> getLoopPositions(InstructionList instructionList){
		ArrayList<Integer> loopPositions = new ArrayList<Integer>();

		for (InstructionHandle handle : instructionList.getInstructionHandles()) {
			Instruction inst = handle.getInstruction();

			if (inst instanceof IINC) {
				InstructionHandle nextInstructionHandle = handle.getNext();
				Instruction nextInstruction = nextInstructionHandle.getInstruction();
				Integer index = ((IINC) inst).getIndex();

				if (nextInstruction instanceof GotoInstruction) {
					InstructionHandle targetHandle = ((GotoInstruction) nextInstruction).getTarget();
					Integer start = targetHandle.getPosition() - 2;
					loopPositions.add(start);
					loopPositions.add(nextInstructionHandle.getPosition());
					loopPositions.add(index);
				}
			}

		}
		return loopPositions;
	}

	//replaces a variable load by loading a constant ill try replacing it with something more efficient
	public void replaceVarLoad(ConstantPoolGen cpgen,InstructionList instructionList, InstructionHandle handle,Number stackTop){
		if (stackTop instanceof Integer){
			handle.setInstruction(new LDC(cpgen.addInteger((Integer) stackTop)));
		}
		else if (stackTop instanceof Float){
			instructionList.insert(handle, new LDC(cpgen.addFloat((Float) stackTop)));
		}
		else if (stackTop instanceof Long){
			instructionList.insert(handle, new LDC2_W(cpgen.addLong((Long) stackTop)));
		}
		else if (stackTop instanceof Double){
			instructionList.insert(handle, new LDC2_W(cpgen.addDouble((Double) stackTop)));
		}
	}

	public void removeLDCs(InstructionHandle handle, InstructionList instructionList, int numberToDelete){
		int deleted = 0;
		InstructionHandle handleToCheck = handle.getPrev();
		while (deleted != numberToDelete) {

			if ((handleToCheck.getInstruction() instanceof LDC) || (handleToCheck.getInstruction() instanceof LDC2_W)) {
				deleted++;
				if (deleted < numberToDelete) {
					handleToCheck = handleToCheck.getPrev();
					deleteInstruction(handleToCheck.getNext(), instructionList);
					continue;
				} else {
					deleteInstruction(handleToCheck, instructionList);
				}

			}
			else if (handleToCheck.getPrev() == null) {
				break;
			}
			handleToCheck = handleToCheck.getPrev();
		}
	}
	public Number getConstantValue(ConstantPoolGen cpgen, InstructionHandle handle ){
		Instruction instruction = handle.getInstruction();
		if ((instruction instanceof LDC)) {
			return (Number) (((LDC) handle.getInstruction()).getValue(cpgen));
		}
		else if (instruction instanceof LDC2_W) {
			return (((LDC2_W) handle.getInstruction()).getValue(cpgen));
		}
		else if (instruction instanceof BIPUSH) {
			return (((BIPUSH) handle.getInstruction()).getValue());
		}
		else if (instruction instanceof SIPUSH) {
			return (((SIPUSH) handle.getInstruction()).getValue());
		}
		return null;
	}



	public void optimizeMethod( ClassGen cgen, ConstantPoolGen cpgen, Method method){
		Code methodCode = method.getCode(); //get the code
		Stack<Number> constantStack = new Stack<Number>();
		HashMap<Integer, Number> variables = new HashMap<Integer, Number>();

		InstructionList instructionList = new InstructionList(methodCode.getCode());

		System.out.println("Optimising method: " + method.getName());

		MethodGen methodGen = new MethodGen(method.getAccessFlags(), method.getReturnType(), method.getArgumentTypes(), null, method.getName(), cgen.getClassName(), instructionList, cpgen);

		boolean skipNextArithmeticOperation = false;
		boolean deletedIf = false;
		int constants = 0;

		// search for loops to know if you should apply folding to the variable or not
		ArrayList<Integer> loopArray = getLoopPositions(instructionList); //positions of loops
		for (InstructionHandle handle : instructionList.getInstructionHandles()) {

			if (handle.getInstruction() == null) {
				continue;//if the instruction is null go to next
			}

			boolean inLoop = false;
			for (int i = 0; i < loopArray.size(); i += 3) {//each loop is represented by 3 values, start, end and the index of the incremented value
				Integer start = loopArray.get(i);
				Integer end = loopArray.get(i + 1);
				if (handle.getPosition() <= end && handle.getPosition() >= start) {
					inLoop = true;
				}
			}

			boolean isLDCorPush = (handle.getInstruction() instanceof LDC) || (handle.getInstruction() instanceof LDC_W) || (handle.getInstruction() instanceof LDC2_W) || (handle.getInstruction() instanceof SIPUSH) || (handle.getInstruction() instanceof BIPUSH);;
			boolean isArithmeticInst = (handle.getInstruction() instanceof ArithmeticInstruction);
			boolean isConst = (handle.getInstruction() instanceof ICONST || handle.getInstruction() instanceof FCONST || handle.getInstruction() instanceof LCONST || handle.getInstruction() instanceof DCONST);
			boolean isStore = (handle.getInstruction() instanceof StoreInstruction);
			boolean isLoadInst = (handle.getInstruction() instanceof LoadInstruction);
			boolean isLongComparison = (handle.getInstruction() instanceof LCMP);

			//check if the instruction are in the loop
			if (inLoop) {
				if (isLoadInst) {
					boolean removeNextLoad = true;
					for (int i = 0; i < loopArray.size(); i += 3) {
						int loopVarIndex = loopArray.get(i + 2);
						int currentIndex = ((LoadInstruction) handle.getInstruction()).getIndex();
						//here we check if the variable that is loaded is not the variable incremented by the loop
						if (loopVarIndex == currentIndex) {
							removeNextLoad = false;
							skipNextArithmeticOperation = true; //we do not want to optimize any arithmetic operation where the loop variable is present
						}
					}
					if (removeNextLoad) {
						if (!(handle.getInstruction() instanceof ALOAD)) {
							int index = ((LoadInstruction) handle.getInstruction()).getIndex();
							Number stackTop = variables.get(index);
							constantStack.push(stackTop);
							instructionList.insert(handle, new PUSH(cpgen, stackTop));
						}
					}
				}
				if (isArithmeticInst && (!skipNextArithmeticOperation)) {
					if (constants >= 2) {
						removeLDCs(handle, instructionList, 1);
					}
					//todo performartihmeticop
					Number stackTop = constantStack.pop();
					constants++;
					instructionList.insert(handle, new PUSH(cpgen, stackTop));
					constantStack.push(stackTop);
					deleteInstruction(handle, instructionList);
				}
				if (isArithmeticInst && skipNextArithmeticOperation) {
					skipNextArithmeticOperation = false;
				}
			}


			if ( isLDCorPush){
				Number constantValue = getConstantValue(cpgen, handle);
				constantStack.push(constantValue);
				deleteInstruction(handle,instructionList);
			}
			else if (isLongComparison){
				Number val1 = constantStack.pop();
				Number val2 = constantStack.pop();
				Number toPush = null;
				if ((Long) val1 > (Long)val2){
					toPush = 1
				}
				else if ((Long) val1 < (Long)val2){
					toPush = -1;
				}
				else{
					toPush = 0;
				}
				constantStack.push(toPush);
				deleteInstruction(handle,instructionList);
			}
			else if (isConst){
				Number val = null;
				if (handle.getInstruction() instanceof ICONST) {
					val = (((ICONST) handle.getInstruction()).getValue());
				}
				else if (handle.getInstruction() instanceof FCONST) {
					val = (((FCONST) handle.getInstruction()).getValue());
				}
				else if (handle.getInstruction() instanceof LCONST) {
					val = (((LCONST) handle.getInstruction()).getValue());
				}
				else if (handle.getInstruction() instanceof DCONST) {
					val = (((DCONST) handle.getInstruction()).getValue());
				}
				constantStack.push(val);

			}
			else if (isArithmeticInst){
				if (constants >= 2){
					removeLDCs(handle,instructionList,2);
				}
				else{
					removeLDCs(handle,instructionList,1);
				}
				performArithOp(handle);
				Number stackTop = constantStack.pop();
				instructionList.insert(handle, new PUSH(cpgen, stackTop));
				constantStack.push(stackTop);
				deleteInstruction(handle,instructionList);
			}
			else if (isStore){
				Number val = constantStack.pop();
				int index = ((StoreInstruction) handle.getInstruction()).getIndex();
				variables.put(index,val);
				deleteInstruction(handle,instructionList);
			}
			else if (isLoadInst){
				if (!(handle.getInstruction() instanceof ALOAD)){
					int index = ((LoadInstruction) handle.getInstruction()).getIndex();
					Number stackTop = variables.get(index);
					constants++;
					constantStack.push(stackTop);
					instructionList.insert(handle, new PUSH(cpgen, stackTop));
					deleteInstruction(handle,instructionList);
				}
			}
		}
		try{
			instructionList.setPositions(true);
		}
		catch (Exception e){
			System.out.println("problem setting positions for instructions");
		}
		for (InstructionHandle handle : instructionList.getInstructionHandles()){
			System.out.println(handle.toString());
		}
		methodGen.setMaxStack();
		methodGen.setMaxLocals();
		Method newMethod = methodGen.getMethod();
		//replace old method with the new one
		cgen.replaceMethod(method,newMethod);

	}

	public void optimize()
	{
		ClassGen cgen = new ClassGen(original);
		cgen.setMajor(50);
		ConstantPoolGen cpgen = cgen.getConstantPool();

		// Implement your optimization here
		Method[] methods = cgen.getMethods();
		for (Method m : methods){
			//todo call optimizeMethod
			optimizeMethod(cgen,cpgen,m);
		}
        
		this.optimized = gen.getJavaClass();
	}

	
	public void write(String optimisedFilePath)
	{
		this.optimize();

		try {
			FileOutputStream out = new FileOutputStream(new File(optimisedFilePath));
			this.optimized.dump(out);
		} catch (FileNotFoundException e) {
			// Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// Auto-generated catch block
			e.printStackTrace();
		}
	}

}

