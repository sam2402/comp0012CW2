
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
	Stack<Number> constantStack;

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

	private ArrayList<Integer> getLoopPositions(InstructionList instructionList){
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

	//replaces a variable load by loading a constant
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


	public void optimizeMethod( ClassGen cgen, ConstantPoolGen cpgen, Method method) throws TargetLostException {
		Code methodCode = method.getCode(); //get the code
		constantStack = new Stack<Number>();
		HashMap<Integer, Number> variables = new HashMap<Integer, Number>();

		InstructionList instructionList = new InstructionList(methodCode.getCode());

		System.out.println("Optimising method: " + method.getName());

		MethodGen methodGen = new MethodGen(method.getAccessFlags(), method.getReturnType(), method.getArgumentTypes(), null, method.getName(), cgen.getClassName(), instructionList, cpgen);

		ArithmeticInstruction operator = null;
		boolean skipNextArithmeticOperation = false;
		boolean deletedIf = false;
		int constants = 0;

		// search for loops to know if you should apply folding to the variable or not
		ArrayList<Integer> loopArray = getLoopPositions(instructionList); //positions of loops
		for (InstructionHandle handle : instructionList.getInstructionHandles()){

			if (handle.getInstruction() == null ){
				continue;//if the instruction is null go to next
			}

			boolean inLoop = false;
			for (int i = 0; i < loopArray.size(); i+=3 ){//each loop is represented by 3 values, start, end and the index of the incremented value
				Integer start = loopArray.get(i);
				Integer end   = loopArray.get(i+1);
				if (handle.getPosition() <= end && handle.getPosition() >= start){
					inLoop = true;
				}
			}

			boolean isLDC = (handle.getInstruction() instanceof LDC) || (handle.getInstruction() instanceof LDC_W) || (handle.getInstruction() instanceof LDC2_W);
			boolean isArithmeticInst = (handle.getInstruction() instanceof ArithmeticInstruction);
			boolean isPush = (handle.getInstruction() instanceof SIPUSH) || (handle.getInstruction() instanceof BIPUSH);
			boolean isConst = (handle.getInstruction() instanceof ICONST || handle.getInstruction() instanceof FCONST || handle.getInstruction() instanceof LCONST || handle.getInstruction() instanceof DCONST);
			boolean isStore = (handle.getInstruction() instanceof StoreInstruction);
			boolean isLoadInst = (handle.getInstruction() instanceof LoadInstruction);
			boolean isComparison = (handle.getInstruction() instanceof IfInstruction);
			boolean isLongComparison = (handle.getInstruction() instanceof LCMP);
			boolean isGoto = (handle.getInstruction() instanceof GotoInstruction);
			boolean isConversion = (handle.getInstruction() instanceof I2D);

			//check if the instruction are in the loop
			if (inLoop == true){
				if (isLoadInst == true){
					boolean removeNextLoad = true;
					for (int i = 0; i < loopArray.size(); i += 3){
						int loopVarIndex = loopArray.get(i+2);
						int currentIndex = ((LoadInstruction) handle.getInstruction()).getIndex();
						//here we check if the variable that is loaded is not the variable incremented by the loop
						if (loopVarIndex == currentIndex){
							removeNextLoad = false;
							skipNextArithmeticOperation = true; //we do not want to optimize any arithmetic operation where the loop variable is present
						}
					}
					if (removeNextLoad == true){
						if(!(handle.getInstruction() instanceof ALOAD)){
							int index = ((LoadInstruction) handle.getInstruction()).getIndex();
							Number stackTop = variables.get(index);
							constantStack.push(stackTop);
							replaceVarLoad(cpgen, instructionList, handle, stackTop);
						}
					}
				}

			}
			if (isArithmeticInst && (!skipNextArithmeticOperation)){

				if (constants>=2){
					removeInstruction(handle, instructionList,1);
				}
				operator = ((ArithmeticInstruction) handle.getInstruction());
				doArithmeticOperation(operator);
				constants++;

				Number newValue = constantStack.pop();
				if (newValue instanceof Double){
					instructionList.insert(handle, new LDC2_W(cpgen.addDouble((Double) newValue)));
				}else if (newValue instanceof Long) {
					instructionList.insert(handle, new LDC2_W(cpgen.addLong((Long) newValue)));
				} else if (newValue instanceof Integer) {
					instructionList.insert(handle, new LDC(cpgen.addInteger((Integer) newValue)));
				} else if (newValue instanceof Float) {
					instructionList.insert(handle, new LDC(cpgen.addFloat((Float) newValue)));
				}

				constantStack.push(newValue);
				instructionList.delete(handle);
			}
			continue;
		}

	}

	private void removeInstruction(InstructionHandle handle, InstructionList instrL, int howManyToDelete) throws TargetLostException {
		int count = 0;
		InstructionHandle handleCheck = handle.getPrev();
		while( count != howManyToDelete) {
			Instruction instr = handleCheck.getInstruction();
			if ((instr instanceof LDC) || (instr instanceof LDC_W)) {
				count++;
				if (count < howManyToDelete) {
					handleCheck = handleCheck.getPrev();
					instrL.delete(handleCheck.getNext());
					continue;
				} else {
					instrL.delete(handleCheck);
				}

			} else if (handleCheck.getPrev() == null) {
				break;
			}
			handleCheck = handleCheck.getPrev();
		}}

//	private void deleteInstr(InstructionHandle handle, InstructionList lst){
//		try {
//			lst.delete(handle);
//		} catch (TargetLostException e) {
//			InstructionHandle[] targets = e.getTargets();
//
//			for (InstructionHandle target : targets) {
//				InstructionTargeter[] targeters = target.getTargeters();
//				for (InstructionTargeter targeter : targeters)
//					targeter.updateTarget(target, null);
//			}
//		}
//	}

	private void doArithmeticOperation(ArithmeticInstruction operator){
		Number first = constantStack.pop();
		Number second = constantStack.pop();

		if (operator instanceof IADD) {
			Number newValue = second.intValue() + first.intValue();
			constantStack.push(newValue);
		} else if (operator instanceof LADD) {
			Number newValue = second.longValue() + first.longValue();
			constantStack.push(newValue);
		} else if (operator instanceof FADD) {
			Number newValue = second.floatValue() + first.floatValue();
			constantStack.push(newValue);
		} else if (operator instanceof DADD) {
			Number newValue = second.doubleValue() + first.doubleValue();
			constantStack.push(newValue);
		}
		else if (operator instanceof IMUL) {
			Number newValue = second.intValue() * first.intValue();
			constantStack.push(newValue);
		} else if (operator instanceof LMUL) {
			Number newValue = second.longValue() * first.longValue();
			constantStack.push(newValue);
		} else if (operator instanceof FMUL) {
			Number newValue = second.floatValue() * first.floatValue();
			constantStack.push(newValue);
		} else if (operator instanceof DMUL) {
			Number newValue = second.doubleValue() * first.doubleValue();
			constantStack.push(newValue);
		}
		else if (operator instanceof ISUB) {
			Number newValue = second.intValue() - first.intValue();
			constantStack.push(newValue);
		} else if (operator instanceof LSUB) {
			Number newValue = second.longValue() - first.longValue();
			constantStack.push(newValue);
		} else if (operator instanceof FSUB) {
			Number newValue = second.floatValue() - first.floatValue();
			constantStack.push(newValue);
		} else if (operator instanceof DSUB) {
			Number newValue = second.doubleValue() - first.doubleValue();
			constantStack.push(newValue);
		}
		else if (operator instanceof IDIV) {
			Number newValue = second.intValue() / first.intValue();
			constantStack.push(newValue);
		} else if (operator instanceof LDIV) {
			Number newValue = second.longValue() / first.longValue();
			constantStack.push(newValue);
		} else if (operator instanceof FDIV) {
			Number newValue = second.floatValue() / first.floatValue();
			constantStack.push(newValue);
		} else if (operator instanceof DDIV) {
			Number newValue = second.doubleValue() / first.doubleValue();
			constantStack.push(newValue);
		}

	}

	public void optimize()
	{
		ClassGen cgen = new ClassGen(original);
		ConstantPoolGen cpgen = cgen.getConstantPool();

		// Implement your optimization here
		Method[] methods = cgen.getMethods();
		for (Method m : methods){
			//todo call optimizeMethod

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
