package edu.nyu.oop;

import org.slf4j.Logger;
import xtc.tree.GNode;
import xtc.tree.Node;
import xtc.tree.Visitor;
import xtc.util.SymbolTable;
import edu.nyu.oop.util.SymbolTableUtil;
import xtc.util.Runtime;

import xtc.lang.JavaEntities;

import xtc.type.*;

import edu.nyu.oop.util.NodeUtil;
import edu.nyu.oop.util.SymbolTableBuilder;
//import edu.nyu.oop.BigArray;
//import edu.nyu.oop.PrimitiveArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Stack;

/* Building C++ style AST given a root node of a java AST */
public class Phase4 {

    private Runtime runtime;
    private HashMap<String, String> childrenToParents = new HashMap<String, String>();
    private HashMap<String, ArrayList<Phase1.Initializer>> inits = new HashMap<String, ArrayList<Phase1.Initializer>>();
    private HashMap<String, ArrayList<Phase1.Initializer>> formerInits = new HashMap<String, ArrayList<Phase1.Initializer>>();

    public ArrayList<BigArray> bigArrays = new ArrayList<BigArray>();
    public ArrayList<PrimitiveArray> primitiveArrays = new ArrayList<PrimitiveArray>();

    public Phase4(Runtime runtime) {
        this.runtime = runtime;
    }

    public Phase4(Runtime runtime, HashMap<String, String> childrenToParents, HashMap<String, ArrayList<Phase1.Initializer>> inits) {
        this.runtime = runtime;
        this.childrenToParents = childrenToParents;
        this.inits = inits;

        // Set<String> set = this.inits.keySet();
        // for (Object key : set) {

        //     ArrayList<Phase1.Initializer> list = this.inits.get((String) key);

        //     System.out.println((String) key);

        //     for (Object o : list) {
        //         if (o instanceof Phase1.Initializer) {

        //             Phase1.Initializer init = (Phase1.Initializer) o;
        //             System.out.println(init.name + " " + init.initial + " " + init.value);
        //         }
        //     }
        // }

        for (String s : this.inits.keySet()) {
            ArrayList<Phase1.Initializer> tmpInitAL = new ArrayList<Phase1.Initializer>();
            ArrayList<Phase1.Initializer> initAL = this.inits.get(s);
            for (int i = 0; i < initAL.size(); i ++) {
                tmpInitAL.add(initAL.get(i));
            }
            this.formerInits.put(s, tmpInitAL);
        }

        resolveInitializers();

        // set = this.inits.keySet();
        // for (Object key : set) {

        //     ArrayList<Phase1.Initializer> list = this.inits.get((String) key);

        //     System.out.println((String) key);

        //     for (Object o : list) {
        //         if (o instanceof Phase1.Initializer) {

        //             Phase1.Initializer init = (Phase1.Initializer) o;
        //             System.out.println(init.name + " " + init.initial + " " + init.value);
        //         }
        //     }
        // }
    }

    public void resolveInitializers() {
        for (String key : childrenToParents.keySet()) {
            String toUpdate = key;
            Stack<String> stack = new Stack<String>();
            stack.push(key);
            String temp = key;
            while (!childrenToParents.get(temp).equals("")) {
                stack.push(childrenToParents.get(temp));
                temp = childrenToParents.get(temp);
            }
            ArrayList<Phase1.Initializer> tmpAL = inits.get(stack.pop());
            ArrayList<Phase1.Initializer> start = new ArrayList<Phase1.Initializer>();
            if (tmpAL != null) {

                for (int i = 0; i < tmpAL.size(); i ++) {
                    start.add(tmpAL.get(i));
                }
            }
            while (!stack.empty()) {
                ArrayList<Phase1.Initializer> tempList = inits.get(stack.pop());
                for (Phase1.Initializer elem : tempList) {
                    if (!elem.isStatic) {
                        boolean addFlag = true;
                        for (int i = 0; i < start.size(); i++) {
                            if (elem.name.equals(start.get(i).name) && elem.typeName.equals(start.get(i).typeName)) {
                                start.set(i, elem);
                                addFlag = false;
                            }
                        }
                        if (addFlag) start.add(elem);
                    }
                }
            }
            inits.put(toUpdate, start);
        }
    }

    /* process a list of nodes */
    public List<GNode> process(List<GNode> l) {

        ArrayList<GNode> cppAst = new ArrayList<GNode>();

        for (Object o : l) {
            if (o instanceof Node) {
                cppAst.add((GNode) o);
            }
        }

        for (Object o : cppAst) {
            if (o instanceof Node) {

                SymbolTable table = new SymbolTableBuilder(runtime).getTable((GNode) o);
                Phase4Visitor visitor = new Phase4Visitor(table, runtime, formerInits, childrenToParents, inits);
                visitor.traverse((Node) o);
            }
        }

        return cppAst;
    }

    /* process a single node */
    public Node runNode(Node n, SymbolTable table) {
        Phase4Visitor visitor = new Phase4Visitor(table, runtime, formerInits, childrenToParents, inits);
        visitor.traverse(n);
        //bigArrays.addAll(visitor.bigArrays);
        //primitiveArrays.addAll(visitor.primitiveArrays);
        return n;
    }

    /* Visitor class used to modify java AST to C++ AST */
    public static class Phase4Visitor extends Visitor {

        private Logger logger = org.slf4j.LoggerFactory.getLogger(this.getClass());

        private Runtime runtime;

        private String currentClass = null;
        private Object extension = null;
        private boolean isMain = false;
        private String methodName = "";
        private String packageInfo = "";
        private boolean constructorFlag = false;
        private HashMap<String, String> ctp;
        private HashMap<String, ArrayList<Phase1.Initializer>> completedInits;
        private HashMap<String, ArrayList<Phase1.Initializer>> formerInits;        
        private boolean defaultConstructorNeeded = false;

        private SymbolTable table;

        public ArrayList<BigArray> bigArrays = new ArrayList<BigArray>();
        public ArrayList<PrimitiveArray> primitiveArrays = new ArrayList<PrimitiveArray>();

        public Phase4Visitor(SymbolTable table, Runtime runtime, HashMap<String, ArrayList<Phase1.Initializer>> formerInits,
            HashMap<String, String> ctp, HashMap<String, ArrayList<Phase1.Initializer>> completedInits) {
            this.table = table;
            this.runtime = runtime;
            this.ctp = ctp;
            this.completedInits = completedInits;
            this.formerInits = formerInits;
        }

        public String toCppType(String type) {
            String cppType;
            switch (type) {
            case "long":
                cppType = "int64_t";
                break;
            case "int":
                cppType = "int32_t";
                break;
            case "short":
                cppType = "int16_t";
                break;
            case "byte":
                cppType = "int8_t";
                break;
            case "boolean":
                cppType = "bool";
                break;
            default:
                cppType = type;
                break;
            }
            return cppType;
        }

        public boolean isPrimitiveType(String type) {
            if (type.equals("boolean") || type.equals("byte") || type.equals("char") ||
                    type.equals("short") || type.equals("int") || type.equals("long") ||
                    type.equals("float") || type.equals("double")) {
                return true;
            }
            return false;
        }

        public void visitCompilationUnit(GNode n) {

            String packageInfo = "";
            GNode p = (GNode) n.getGeneric(0);
            GNode packageName = (GNode) p.getGeneric(1);
            for (int i = 0; i < packageName.size(); i ++) packageInfo += packageName.get(i).toString() + ".";
            this.packageInfo = packageInfo;
            packageInfo = packageInfo.substring(0, packageInfo.length() - 1);

            table.enter("package(" + packageInfo + ")");
            table.enter(n);
            table.mark(n);

            for (int i = 1; i < n.size(); i++) {
                GNode child = n.getGeneric(i);
                dispatch(child);
            }

            table.exit();
            table.exit();
            table.setScope(table.root());
        }

        /* Visitor for Modifiers
         * Modifiers are not considered in our translator
         */
        public void visitModifiers(GNode n) {
            for (int i = 0; i < n.size(); i ++) {
                if (n.getNode(i).getString(0).equals("static")) {
                    n.set(i, GNode.create("StaticModifer", null));
                } else {
                    n.set(i, null);
                }

            }
            visit(n);
        }

        public void visitCastExpression(GNode n) {
            if (!n.getNode(0).getName().equals("JavaCast")) {
                String castStatement = "__rt::java_cast<";
                String toCast = n.getNode(0).getNode(0).get(0).toString();
                n.setProperty("CastType", toCast);
                castStatement += toCast + ">";
                GNode castNode = GNode.create("JavaCast", castStatement);
                n.set(0, castNode);
            }
            visit(n);
        }

        /* Visitor for ClassDeclaration
         * Modified method name to "__class::method"
         * Adding __this argument to each method
         * Transform this() and super() called in constructors to the
         * form by calling __init();
         */
        public void visitClassDeclaration(GNode n) {

            SymbolTableUtil.enterScope(table, n);
            table.mark(n);

            defaultConstructorNeeded = true;

            //collect class info
            currentClass = (String) n.get(1).toString();
            extension = NodeUtil.dfs(n, "Extension");

            String parentName = "";
            if (extension != null) {
                GNode parentType = (GNode) ((GNode) extension).getNode(0);
                GNode parentTypeNode = (GNode) parentType.getNode(0);
                parentName = parentTypeNode.get(0).toString();
            } else parentName = "Object";

            String defaultConstructor = "__" + currentClass + "::__" + currentClass + "() : ";
            ArrayList<Phase1.Initializer> initializers = completedInits.get(currentClass);
            for (Phase1.Initializer init: initializers) {
                if (!init.isStatic) defaultConstructor += init.name + "(" + init.initial + "), ";
            }
            defaultConstructor += "__vptr(&__vtable) {}";
            n.setProperty("defaultConstructor", defaultConstructor);

            String classInfo = "";
            classInfo += "Class __" + n.get(1).toString() + "::__class() {\n";
            classInfo += "static Class k = new __Class(__rt::literal(\""
                         + this.packageInfo + currentClass + "\"), __"
                         + parentName + "::__class());\n";
            classInfo += "return k;\n";
            classInfo += "}\n";
            n.setProperty("classInfo", classInfo);

            String vtableInit = "__" + currentClass + "_VT __" + currentClass
                                + "::__vtable;\n";
            n.setProperty("vtableInit", vtableInit);

            visit(n);

            if (defaultConstructorNeeded) {
                String initCall = "";
                initCall += currentClass + " __"
                            + currentClass + "::__init("
                            + currentClass + " __this) {\n";
                initCall += "__" + parentName + "::__init(__this);\n";
                        + currentClass + "::__init("
                        + currentClass + " __this) {\n";

                // String parentName = "";
                // if (extension == null) parentName = "Object";
                // else parentName = ((GNode) NodeUtil.dfs((Node) extension, "QualifiedIdentifier")).get(0).toString();


                initCall += "__" + parentName + "::__init(__this);\n";

                initializers = formerInits.get(currentClass);
                for (Phase1.Initializer init: initializers) {
                    if (!init.value.equals("") && !init.isStatic)
                        initCall += "__this -> " + init.name + " = " + init.value + ";\n";
                }
                initCall += "return __this;\n";
                initCall += "}\n";
                n.setProperty("realDefaultConstructor", initCall);
            }

            String staticInit = "";
            initializers = completedInits.get(currentClass);
            for (Phase1.Initializer init : initializers) {
                if (init.isStatic) {
                    if (init.value.equals(""))
                        staticInit += init.typeName + " __" + currentClass + "::" + init.name + " = " + init.initial + ";\n";
                    else
                        staticInit += init.typeName + " __" + currentClass + "::" + init.name + " = " + init.value + ";\n";
                }
            }
            if (!staticInit.equals("")) n.setProperty("staticInit", staticInit);

            extension = null;
            currentClass = null;

            SymbolTableUtil.exitScope(table, n);
        }

        /* Visitor for MethodDeclaration
         * Checking whether variables inside this scope are defined in
         * the class field or inside the method
         * (actually the main work is done in visitBlock(), where block will
         * examine whether variable used inside their scopes are defined inside
         * here we check if those which are set to not be defined in the inner scope
         * have been defined as parameters)
         */
        public void visitMethodDeclaration(GNode n) {

            SymbolTableUtil.enterScope(table, n);
            table.mark(n);

            methodName = table.current().getName();
            if (n.getProperty("mangledName") != null) n.set(3, n.getProperty("mangledName").toString().replace(" ", ""));

            //main function has no field method
            if (n.get(3).toString().equals("main")) {
                isMain = true;
                n.set(2, "int32_t");
                //cannot handling array now
                //n.set(4, GNode.create("Arguments", GNode.create("VoidType")));
                n.set(3, "__" + currentClass + "::" + n.get(3).toString());
                visit(n);
                GNode blockContent = (GNode) NodeUtil.dfs(n, "Block");
                GNode newBlock = GNode.create("Block");
                for (Object o: blockContent) newBlock.add(o);
                newBlock.add(GNode.create("ReturnStatement", "0"));
                n.set(7, newBlock);
                isMain = false;
            } else {
                constructorFlag = false;
                visit(n);
                //constructor
                if (n.get(3).toString().equals(currentClass)) {
                    defaultConstructorNeeded = false;
                    //n.set(0, GNode.create("Modifiers"));
                    n.set(2, currentClass);
                    n.set(3, "__" + currentClass + "::" + "__init");
                    GNode blockContent = (GNode) NodeUtil.dfs(n, "Block");
                    GNode newBlock = GNode.create("Block");
                    if (!constructorFlag) {

                        String parentName = "";
                        if (extension == null) parentName = "Object";
                        else parentName = ((GNode) NodeUtil.dfs((Node) extension, "QualifiedIdentifier")).get(0).toString();



                        newBlock.add(GNode.create("Statement", "__" + parentName + "::__init(__this);\n"));
                        
                        String initStatements = "";
                        ArrayList<Phase1.Initializer> initializers = formerInits.get(currentClass);
                        for (Phase1.Initializer init: initializers) {
                            if (!init.value.equals("") && !init.isStatic)
                                initStatements += "__this -> " + init.name + " = " + init.value + ";\n";
                        }
                        newBlock.add(GNode.create("Statement", initStatements));
                    }
                    if (blockContent != null) {
                        for (Object o : blockContent) newBlock.add(o);
                    }
                    newBlock.add(GNode.create("ReturnStatement", "__this"));
                    n.set(7, newBlock);
                }
                //normal methods
                else {
                    //n.set(0, GNode.create("Modifiers"));
                    n.set(3, "__" + currentClass + "::" + n.get(3).toString());
                }

                Object staticCheck = NodeUtil.dfs(n, "StaticModifer");
                if (null == staticCheck) {
                    //Arguments modify, add __this
                    GNode thisType = GNode.create("Type", GNode.create("QualifiedIdentifier", currentClass), null);
                    GNode thisParameter = GNode.create("FormalParameter",  GNode.create("Modifier"), thisType, null, "__this", null);
                    GNode newParams = GNode.create("FormalParameters");
                    GNode oldParams = (GNode) n.get(4);
                    newParams.add(thisParameter);
                    //fill old arguments
                    for (int j = 0; j < oldParams.size(); j++) newParams.add(oldParams.get(j));
                    n.set(4, newParams);
                }
            }
            methodName = "";
            SymbolTableUtil.exitScope(table, n);
        }

        public void visitThisExpression(GNode n) {
            n.set(0, "__this");
            visit(n);
        }

        public void visitType(GNode n) {
            GNode dimensions = (GNode) NodeUtil.dfs(n, "Dimensions");
            String declaration = "";
            if (dimensions != null) {
                String typeName = n.getNode(0).get(0).toString();
                if (isPrimitiveType(typeName)) {
                    typeName = toCppType(typeName);
                    if (!typeName.equals("int")) primitiveArrays.add(new PrimitiveArray(typeName));
                    // else bigArrays.add(new BigArray(typeName, packageInfo)); // big array no longer needed
                }
                for (int i = dimensions.size() - 1; i > -1; i--) {
                    if (i == dimensions.size() - 1) declaration = "__rt::Array<" + typeName + ">";
                    else declaration = "__rt::Array<" + declaration + ">";
                }
                GNode arrNode = GNode.create("QualifiedIdentifier", declaration);
                n.set(0, arrNode);
                n.set(1, null);  // maybe??
            }
            visit(n);
        }

        public void visitPrimitiveType(GNode n) {
            String typeName = toCppType(n.get(0).toString());
            n.set(0, typeName);
            visit(n);
        }

        public void visitNewArrayExpression(GNode n) {

            GNode dimensions = (GNode) n.getNode(2);
            String declaration = "";
            String typeDef = "";
            String length = "";

            if (dimensions == null) {
                String typeName = n.getNode(0).get(0).toString();
                if (isPrimitiveType(typeName)) typeName = toCppType(typeName);

                GNode concreteDimensions = (GNode) n.getNode(0);

                length = n.getNode(1).getNode(0).get(0).toString();

                for (int i = concreteDimensions.size() - 1; i > -1; i--) {
                    //String length = n.getNode(1).getNode(i).get(0).toString();
                    if (i == concreteDimensions.size() - 1) typeDef = typeName;
                    else typeDef = "__rt::Array<" + typeDef + ">";
                }
                // declaration = "__rt::__Array<" + typeName + ">::__init(new __rt::__Array<" + typeName + ">(__rt::checkNegativeIndex(" + length + ")))"; // temporarily here
                declaration = "__rt::__Array<" + typeDef + ">::__init(new __rt::__Array<" + typeDef + ">(__rt::checkNegativeIndex(" + length + ")))";
            }

            n.set(3, GNode.create("ArrayExpression", declaration));
            visit(n);
        }

        /* this is an old visitNewArrayExpression, DO NOT DELETE! I REPEAT! DO NOT DELETE!
        public void visitNewArrayExpression(GNode n) {

            GNode dimensions = (GNode) n.getNode(2);

            String declaration = "";
            String left = "";
            String right = "";

            if (dimensions == null) {
                String typeName = n.getNode(0).get(0).toString();
                if (isPrimitiveType(typeName)) typeName = toCppType(typeName);

                GNode concreteDimensions = (GNode) n.getNode(0);
                if (concreteDimensions.size() == 1) {
                    String length = n.getNode(1).getNode(0).get(0).toString();
                    left = "({ __rt::Array<" + typeName + "> tmp";
                    right = "new __rt::__Array<" + typeName + ">(" + length + ")>; tmp; })";
                    //declaration += "({ __rt::Array<" + typeName + "> tmp = new __rt::__Array<" + typeName + ">(" + length + ")>; tmp; })";
                }
                else {
                    for (int i = concreteDimensions.size() - 1; i > -1; i--) {
                        if (i == concreteDimensions.size() - 1) {
                            String length = n.getNode(1).getNode(i).get(0).toString();
                            left = "({ __rt::Array<" + left + "> tmp";
                            right = "new __rt::__Array<" + typeName + ">(" + length + ")>; tmp; })";
                            declaration = left + "=" + right;
                        }
                        else {
                            String length = n.getNode(1).getNode(i).get(0).toString();
                            left = "({ __rt::Array<" + left + "> tmp";
                            right = "new __rt::__Array<" + declaration + ">(" + length + ")>; tmp; })";
                            declaration = left + " = " + right;
                        }
                    }
                }
                declaration = left + "=" + right;
            }
            else {
                // nothing for now
            }

            //String typeName = n.getNode(0).get(0).toString();
            //if (isPrimitiveType(typeName)) typeName = toCppType(typeName);
            //String length = n.getNode(1).getNode(0).get(0).toString();
            //String declaration = "({ __rt::Array<" + typeName + "> tmp = new __rt::__Array<" + typeName + ">(" + length + ")>; tmp; })";
            n.set(3, GNode.create("ArrayExpression", declaration));
            //__rt::Array<Object> a = ({ __rt::Array<String> tmp = new __rt::__Array<String>(3); tmp; });
            visit(n);
        }
        */

        public void visitBlockDeclaration(GNode n) {

            SymbolTableUtil.enterScope(table, n);
            table.mark(n);
            visit(n);
            SymbolTableUtil.exitScope(table, n);
        }

        /* Visitor for NewClassExpression
         * Class name should start with "__"
         */
        public void visitNewClassExpression(GNode n) {

            GNode id = (GNode) NodeUtil.dfs(n, "QualifiedIdentifier");
            if (!id.get(0).toString().startsWith("__")) {
                Node oldArgs = n.getNode(3);
                GNode newArgs = GNode.create("Arguments");
                n.set(3, newArgs);
                newArgs.add(GNode.create("Argument", "new __" + id.get(0).toString() + "()"));
                for (int j = 0; j < oldArgs.size(); j++) newArgs.add(oldArgs.get(j));
                id.set(0, "__" + id.get(0) + "::__init");
            }
            visit(n);
        }

        /* Visitor for Block
         * Check whether the variables inside the scope are defined
         * Can also be done using symble table (similar idea here)
         */
        public void visitBlock(GNode n) {

            SymbolTableUtil.enterScope(table, n);
            table.mark(n);

            visit(n);

            SymbolTableUtil.exitScope(table, n);
        }

        public void visitPrimaryIdentifier(GNode n) {
            if (n.get(0).toString().contains("__this")) {
                return;
            }

            if (n.getString(0).equals("System")) {
                return;
            }

            //add this to field data
            if (!isMain) {


                //TODO: this is not working for nested scopes
                if (!table.current().getParent().isDefinedLocally(n.get(0).toString())) {
                    n.set(0, "__this -> " + n.get(0).toString());
                }
            }

            visit(n);
        }

        public void visitSubscriptExpression(GNode n) {
            if (null == n.getProperty("Store")) {
                n.setProperty("Store", "Access");

                String check;

                check = "__rt::arrayAccessCheck(" + n.getNode(0).getString(0)
                        + ", " + n.getNode(1).getString(0) + ");\n";

                n.setProperty("AccessCheck", check);

            }
        }

        /* Visitor for Selection Expression
         * C++ style "->" for selection
         */
        public void visitSelectionExpression(GNode n) {

            String primaryIdentifier = "";
            Object primaryIdentifierObj = NodeUtil.dfs(n, "PrimaryIdentifier");

            if (primaryIdentifierObj != null) primaryIdentifier = ((GNode) primaryIdentifierObj).get(0).toString();

            if (completedInits.keySet().contains(primaryIdentifier)) {
                GNode primaryIdentifierNode = (GNode) n.get(0);
                primaryIdentifierNode.set(0, "__" + primaryIdentifierNode.get(0).toString());
                n.set(1, "::" + n.get(1).toString());
            } else if (n.get(1).toString().equals("length") && primaryIdentifierObj != null) {
                GNode primaryIdentifierNode = (GNode) n.get(0);
                String newPrimaryIdentifier = "({__rt::checkNotNull(" + primaryIdentifier + ");";
                primaryIdentifierNode.set(0, newPrimaryIdentifier);
                String length = primaryIdentifier + "->length;})" ;
                n.set(1, length);
            } else {
                for (int i = 1; i < n.size(); i++) {
                    if (n.get(i) instanceof String) {
                        if (!n.get(i).toString().startsWith("-> ")) {
                            n.set(i, "-> " + n.get(i).toString());
                        }
                    }
                }
            }
            visit(n);
        }

        public void visitForStatement(GNode n) {
            SymbolTableUtil.enterScope(table, n);
            table.mark(n);
            visit(n);
            SymbolTableUtil.exitScope(table, n);
        }

        public void visitRelationalExpression(GNode n) {
            visit(n);
        }


        public void visitExpressionStatement(GNode n) {

            Node expressionNode = n.getNode(0);

            System.out.println("before the error");

<<<<<<< HEAD
            if (expressionNode.hasName("Expression")) {
                if (expressionNode.getNode(0).hasName("SubscriptExpression") && expressionNode.getString(1).equals("=")) {

                    expressionNode.getNode(0).setProperty("Store", "Store");
=======
                if (nn.getNode(0).hasName("SubscriptExpression")&&nn.getString(1).equals("=")) {
                    
                    nn.getNode(0).setProperty("Store", "Store");
>>>>>>> 0a7c44affff300dd935a713d138a10f179a5e9bb

                    GNode newBlock = GNode.create("ExpressionBlock");

                    GNode tmpDef = GNode.create("ExpressionStatement",
                                                GNode.create("DefExpression", "Object tmp", "=", expressionNode.getNode(2)));

                    GNode check = GNode.create("Check");
                    check.add("__rt::arrayStoreCheck");
                    check.add(expressionNode.getNode(0).getNode(0));
                    check.add(expressionNode.getNode(0).getNode(1));
                    check.add(GNode.create("PrimaryIdentifier", "tmp"));

                    GNode realExpression = GNode.create("realExpression", expressionNode.getNode(0), "=", GNode.create("PrimaryIdentifier", "tmp"));

                    newBlock.add(tmpDef);
                    newBlock.add(check);
                    newBlock.add(realExpression);

                    n.set(0, newBlock);
                }
            }

            visit(n);
        }


        /* Visitor for Selection Expression
         * C++ style "->" for calling class methods
         */
        public void visitCallExpression(GNode n) {

            visit(n);

            //collect info
            Object obj = n.getNode(0);
            GNode selectionStatementNode = null;
            GNode primaryIdentifierNode = null;

            boolean undone = true;

            if (obj != null) {
                selectionStatementNode = (GNode) obj;
                obj = NodeUtil.dfs(selectionStatementNode, "PrimaryIdentifier");
                if (obj != null) primaryIdentifierNode = (GNode) obj;
            }

            if (n.getProperty("mangledName") != null) {
                n.set(2, n.getProperty("mangledName").toString().replace(" ", ""));
            }

            //method in current class
            if (!isMain) {

                if (selectionStatementNode == null) {

                    n.setProperty("noblock", "init");

                    undone = false;

                    //call __init
                    if (n.get(2).toString().equals("this")) {

                        constructorFlag = true;
                        n.set(2, "__" + currentClass + "::__init");

                        // String initStatements= "";
                        // ArrayList<Phase1.Initializer> initializers = completedInits.get(currentClass);
                        // for (Phase1.Initializer init: initializers) {
                        //     if (!init.value.equals("") && !init.isStatic)
                        //         initStatements += "__this -> " + init.name + " + " + init.value + ";\n";
                        // }
                        // n.setProperty("initStatements", initStatements);
                    }

                    //call parent's __init
                    else if (n.get(2).toString().equals("super")) {

                        undone = false;
                        n.setProperty("noblock", "init");

                        constructorFlag = true;

                        String parentName = "";
                        if (extension == null) parentName = "Object";
                        else parentName = ((GNode) NodeUtil.dfs((Node) extension, "QualifiedIdentifier")).get(0).toString();

                        GNode arguments = GNode.create("Arguments");
                        n.set(2, "__" + parentName + "::__init");

                        String initStatements = "";
                        ArrayList<Phase1.Initializer> initializers = formerInits.get(currentClass);
                        for (Phase1.Initializer init: initializers) {
                            System.out.println(init.name);
                            if (!init.value.equals("") && !init.isStatic)
                                initStatements += "__this -> " + init.name + " = " + init.value + ";\n";
                        }
                        n.setProperty("initStatements", initStatements);
                    } else {
                        n.set(2, "__this -> __vptr -> " + n.get(2));
                    }

                    //change arguments
                    GNode newArgs = GNode.create("Arguments");
                    newArgs.add(GNode.create("ThisExpression", "__this"));
                    GNode oldArgs = (GNode) n.get(3);;
                    n.set(3, newArgs);
                    if (oldArgs != null) {
                        for (Object arg: oldArgs) {
                            if ((!arg.equals(newArgs.get(0)))&&(arg != null)) newArgs.add(arg);
                        }
                    }
                }
            }

            if (selectionStatementNode != null && primaryIdentifierNode != null) {

                if (primaryIdentifierNode.get(0).toString().equals("System")) {

                    //with endl
                    if (selectionStatementNode.get(1).toString().equals("-> out") && n.get(2).toString().equals("println")) {

                        undone = false;
                        n.setProperty("noblock", "cout");
                        n.setProperty("cout", "cout");

                        n.set(0, null);
                        n.set(2, "std::cout");
                        GNode arguments = GNode.create("Arguments");
                        GNode oldArg = (GNode) n.get(3);
                        for (Object a : oldArg) {
                            arguments.add(a);
                        }
                        arguments.add("std::endl");
                        n.set(3, arguments);
                    }

                    //without endl
                    if (selectionStatementNode.get(1).toString().equals("-> out") && n.get(2).toString().equals("print")) {

                        undone = false;
                        n.setProperty("noblock", "cout");
                        n.setProperty("cout", "cout");

                        n.set(0, null);
                        n.set(2, "std::cout");
                    }
                }
            }

            //calling a method defined in a class
            obj = n.get(0);

            if (obj instanceof GNode) {

                GNode child = (GNode) obj;

                if (child.hasName("SuperExpression")) {
                    String parentName = "";
                    if (extension == null) {
                        parentName = "Object";
                    } else {
                        parentName = ((GNode) NodeUtil.dfs((Node) extension, "QualifiedIdentifier")).get(0).toString();
                    }

                    n.set(0, "(new __" + parentName + "())");

                    n.set(2, "-> __vptr -> " + n.get(2).toString());

                    GNode arguments = GNode.create("Arguments");

                    arguments.add(GNode.create("Argument", "__this"));
                    GNode oldArg = (GNode) NodeUtil.dfs(n, "Arguments");
                    n.set(3, arguments);

                    if (oldArg != null) {
                        for (Object oo : oldArg) {
                            if ((!oo.equals(arguments.get(0)))&&(oo != null)) {
                                arguments.add(oo);
                            }
                        }
                    }
                    return;
                }

                if (n.get(0) != null) {


                    //start the method from vtable
                    String methodName = (String) n.get(2);

                    if (n.getProperty("methodDispatchType").toString().equals("virtual")) {
                        n.set(2, "-> __vptr -> " + methodName);

                        //add the object to arguments
                        GNode arguments = GNode.create("Arguments");

                        arguments.add(GNode.create("PrimaryIdentifier", "tmp"));
                        GNode oldArg = (GNode) NodeUtil.dfs(n, "Arguments");
                        n.set(3, arguments);

                        if (oldArg != null) {
                            for (Object oo : oldArg) {
                                if (((!oo.equals(arguments.get(0)))&&(oo != null))) {
                                    arguments.add(oo);
                                }
                            }
                        }
                    }

                    if (n.getProperty("methodDispatchType").toString().equals("private")) {
                        n.set(2, "." + methodName);

                        //add the object to arguments
                        GNode arguments = GNode.create("Arguments");

                        arguments.add(GNode.create("PrimaryIdentifier", "tmp"));
                        GNode oldArg = (GNode) NodeUtil.dfs(n, "Arguments");
                        n.set(3, arguments);

                        if (oldArg != null) {
                            for (Object oo : oldArg) {
                                if (((!oo.equals(arguments.get(0)))&&(oo != null))) {
                                    arguments.add(oo);
                                }
                            }
                        }
                    }

                    if (n.getProperty("methodDispatchType").toString().equals("static")) {
                        n.set(2, "::" + methodName);
                    }
                }
            }



            GNode callExpressionBlock = GNode.create("CallExpressionBlock");
            String tmpDef = "";


            if (undone) {

                if (n.getNode(0).hasName("CallExpression")) {
                    tmpDef = n.getNode(0).getProperty("methodReturnType").toString();
                }
                if (n.getNode(0).hasName("PrimaryIdentifier")) {
                    String primaryKey = n.getNode(0).getString(0);
                    if (completedInits.keySet().contains(primaryKey)) {
                        tmpDef = primaryKey;
                    } else {
                        tmpDef = ((VariableT) table.current().lookup(n.getNode(0).get(0).toString())).getType().toString();
                    }

                }
                if (n.getNode(0).hasName("CastExpression")) {
                    tmpDef = n.getNode(0).getProperty("CastType").toString();
                }
                if (n.getNode(0).hasName("SelectionExpression")) {
                    String primaryKey = n.getNode(0).getNode(0).getString(0);
                    String secondaryKey = n.getNode(0).getString(1).replaceAll("->", "").replaceAll(" ", "");
                    String classIn = "";

                    if (primaryKey.equals("__this")) {
                        classIn = currentClass;
                    } else {

                        classIn = (((VariableT)table.current().lookup(primaryKey)).getType().toString()).replaceAll("\\(|\\)", "");
                        String[] classInDetail = classIn.split("\\.");
                        classIn = classInDetail[classInDetail.length - 1];
                    }

                    ArrayList<Phase1.Initializer> classFields = completedInits.get(classIn);

                    for (int i = 0; i < classFields.size(); i++) {
                        if (classFields.get(i).name.equals(secondaryKey)) {
                            tmpDef = classFields.get(i).typeName;
                            break;
                        }
                    }
                }

                tmpDef = tmpDef.replaceAll("\\(|\\)", "");

                String[] tmpClass = tmpDef.split("\\.");

                if (tmpClass.length > 0) {
                    tmpDef = tmpClass[tmpClass.length - 1];
                }
<<<<<<< HEAD
                System.out.println("============");
                System.out.println(tmpDef);

=======
                
>>>>>>> 0a7c44affff300dd935a713d138a10f179a5e9bb
                if (n.getProperty("methodDispatchType").toString().equals("static")) {
                    n.set(0, GNode.create("PrimaryIdentifier", "__" + tmpDef));
                    return;
                }

                GNode def = GNode.create("ExpressionStatement", tmpDef + " tmp", " = ", n.getNode(0));


                GNode nullCheck = GNode.create("Check", "__rt::checkNotNull", GNode.create("PrimaryIdentifier", "tmp"));

                callExpressionBlock.add(def);
                callExpressionBlock.add(nullCheck);


                GNode realExpression = GNode.create("ExpressionStatement");
                GNode realCallExpression = GNode.create("RealCallExpression");
                realCallExpression.add(GNode.create("PrimaryIdentifier", "tmp"));

                for (int i = 1; i < n.size(); i++) {
                    realCallExpression.add(n.get(i));
                }

                realExpression.add(realCallExpression);

                callExpressionBlock.add(realExpression);

                n.set(0, callExpressionBlock);

                for (int i = 1; i < n.size(); i++) {
                    n.set(i, null);
                }
            }
        }

        public void traverse(Node n) {
            super.dispatch(n);
        }


        public void visit(Node n) {
            for (int i = 0; i < n.size(); ++i) {
                Object o = n.get(i);
                if (o instanceof Node) {
                    Object o1 = dispatch((Node) o);
                    if (o1 != null && o1 instanceof Node) {
                        n.set(i, o1);
                    }
                }
            }
        }

    }
}