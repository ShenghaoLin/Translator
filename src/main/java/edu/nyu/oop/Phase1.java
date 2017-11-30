/**
 * Phase 1 visitor that traverses all dependencies recrusively and adds nodes of all
 * dependencies, furthermore checks for duplciate files to avoid duplicate nodes
 * using hash list of absolute paths of files containing Java code
 *
 * @author Shenghao Lin
 * @author Sai Akhil
 * @author Goktug Saatcioglu
 * @author Sam Holloway
 *
 * @verion 1.0
 */

package edu.nyu.oop;

import edu.nyu.oop.util.JavaFiveImportParser;
import edu.nyu.oop.util.SymbolTableUtil;
import edu.nyu.oop.util.NodeUtil;
import edu.nyu.oop.util.TypeUtil;
import xtc.Constants;
import xtc.lang.Java;
import xtc.lang.JavaEntities;
import xtc.tree.GNode;
import xtc.tree.Node;
import xtc.tree.Visitor;
import xtc.type.*;
import xtc.util.Runtime;
import xtc.util.SymbolTable;


import java.io.File;
import java.util.*;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Phase1 {

    /* default constructor */
    public Phase1() {}

    /** parse the Java files and their dependencies
      *
      * @param   n  Node of type Node
      * @return     List of Java ASTs
      */
    public static List<GNode> parse(Node n) {

        GNode node = (GNode) n;
        Set<Path> paths = new HashSet<Path>();
        List<GNode> ast = new ArrayList<GNode>();

        parse(node, paths, ast);

        return ast;
    }

    /** parse the Java files and their dependencies recursively
      *
      * @param   node  Node of type Node
      * @param  paths  Set of paths
      * @param    ast  List of ASTs
      */
    private static void parse(GNode node, Set<Path> paths, List<GNode> ast) {

        // use a queue of nodes to find dependencies and process them
        Queue<GNode> nodes = new ArrayDeque<GNode>();
        nodes.add(node);

        while(!nodes.isEmpty()) {

            GNode next = nodes.poll();

            //test if seen to avoid cyclical dependencies
            String loc = next.getLocation().file;

            // obtain path and convert  to absolute path to ensure uniqueness
            Path path = Paths.get(loc);
            path = path.toAbsolutePath();

            // if file hasn't been visited, process it
            if(!paths.contains(path)) {
                paths.add(path);
                ast.add(next);
                nodes.addAll(JavaFiveImportParser.parse(next));
            }
        }
    }

    public static void mangle(Runtime runtime, SymbolTable table, Node n) {

        Mangler mangler = new Mangler(runtime, table);

        mangler.dispatch(n);
        mangler.dispatch(n);
    }

    public static class Mangler extends Visitor {

        protected SymbolTable table;
        protected Runtime runtime;
        protected HashMap<String, String> methodScopeToMangledName;
        protected HashMap<String, Integer> mangleCounts;
        protected String className = "";
        protected String parentName = "";

        public final List<File> classpath() {
            return JavaEntities.classpath(runtime);
        }

        public Mangler(Runtime runtime, SymbolTable table) {
            this.runtime = runtime;
            this.table = table;
            this.methodScopeToMangledName = new HashMap<String, String>();
        }

        //MISC. HELPFUL METHODS
        public Type returnTypeFromCallExpression(Node n) {
            VariableT callExpObjectLookup = (VariableT) table.lookup(n.getNode(0).get(0).toString());
            String callExpMethodName = (String) n.getString(2);
            Type callExpObjectType = callExpObjectLookup.getType();
            List<Type> callExpActuals = JavaEntities.typeList((List) dispatch(n.getNode(3)));
            MethodT callExpMethod =
                JavaEntities.typeDotMethod(table, classpath(), callExpObjectType, true, callExpMethodName, callExpActuals);
            return callExpMethod.getResult();
        }

        //VISIT METHODS
        public void visitCompilationUnit(GNode n) {
            String packageScope = null == n.get(0) ? visitPackageDeclaration(null) : (String) dispatch(n.getNode(0));
            table.enter(packageScope);
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

        public String visitPackageDeclaration(GNode n) {
            String canonicalName = null == n ? "" : (String) dispatch(n.getNode(1));
            final PackageT result = JavaEntities.canonicalNameToPackage(table, canonicalName);
            return JavaEntities.packageNameToScopeName(result.getName());
        }

        public void visitClassDeclaration(GNode n) {
            this.mangleCounts = new HashMap<String, Integer>();
            SymbolTableUtil.enterScope(table, n);
            table.mark(n);

            className = n.get(1).toString();
            Object extension = NodeUtil.dfs(n, "Extension");

            if (extension == null) {
                parentName = "Object";
            } else {
                parentName = ((GNode) NodeUtil.dfs(n, "QualifiedIdentifier")).get(0).toString();
            }


            visit(n);
            className = "";
            parentName = "";
            SymbolTableUtil.exitScope(table, n);
        }


        public void visitMethodDeclaration(GNode n) {
            SymbolTableUtil.enterScope(table, n);
            table.mark(n);

            //Mangle name
            String methodName = n.getString(3);

            if (n.getProperty("mangledName") == null) {

                if ((!methodName.equals("main"))&&(!methodName.equals(className))) {

                    if (!mangleCounts.containsKey(methodName)) mangleCounts.put(methodName, 0);
                    String newMethodName = methodName + "_" + mangleCounts.get(methodName);
                    methodScopeToMangledName.put(table.current().getQualifiedName(), newMethodName);
                    mangleCounts.put(methodName, mangleCounts.get(methodName) + 1);
                    n.setProperty("mangledName", newMethodName);
                }
            }

            visit(n);
            SymbolTableUtil.exitScope(table, n);
        }


        public void visitBlockDeclaration(GNode n) {
            SymbolTableUtil.enterScope(table, n);
            table.mark(n);
            visit(n);
            SymbolTableUtil.exitScope(table, n);
        }


        public void visitBlock(GNode n) {
            SymbolTableUtil.enterScope(table, n);
            table.mark(n);
            visit(n);
            SymbolTableUtil.exitScope(table, n);
        }

        public void visitForStatement(GNode n) {
            SymbolTableUtil.enterScope(table, n);
            table.mark(n);
            visit(n);
            SymbolTableUtil.exitScope(table, n);
        }

        /*
         * Visit a QualifiedIdentifier = Identifier+.
         */
        public String visitQualifiedIdentifier(final GNode n) {
            // using StringBuffer instead of Utilities.qualify() to avoid O(n^2)
            // behavior
            final StringBuffer b = new StringBuffer();
            for (int i = 0; i < n.size(); i++) {
                if (b.length() > 0)
                    b.append(Constants.QUALIFIER);
                b.append(n.getString(i));
            }
            return b.toString();
        }


        // Helper method to construct the Java AST representation of a 'this' expression.
        // The added type annotation is dependent on the class surrounding the current scope
        // during the traversal of the AST.
        public GNode makeThisExpression() {
            GNode _this = GNode.create("ThisExpression", null);
            TypeUtil.setType(_this, JavaEntities.currentType(table));
            return _this;
        }


        public void visitCallExpression(GNode n) {
            visit(n);
            Node receiver = n.getNode(0);
            String methodName = n.getString(2);
            System.out.println(methodName + " received by " + receiver);
            System.out.println(n);
            if (n.getProperty("mangledName") == null) {
                if ((receiver == null) &&
                        (!"super".equals(methodName)) &&
                        (!"this".equals(methodName))) {
                    Type typeToSearch = JavaEntities.currentType(table);

                    List<Type> actuals = JavaEntities.typeList((List) dispatch(n.getNode(3)));
                    MethodT method =
                        JavaEntities.typeDotMethod(table, classpath(), typeToSearch, true, methodName, actuals);

                    if (methodName.equals("overloaded")) System.out.println("over");

                    if (method == null) return;

                    // EXPLICIT THIS ACCESS (if method name isn't defined locally and method is not static, add "this.")
                    if (!TypeUtil.isStaticType(method)) {
                        n.set(0, makeThisExpression());
                    }
                } else if (receiver != null) {
                    //GET MANGLED NAME
                    if(receiver.getName().equals("PrimaryIdentifier")) {
                        Type typeToSearch = null;
                        //STATIC
                        if (JavaEntities.simpleNameToType(table, classpath(), table.current().getQualifiedName(), receiver.get(0).toString()) != null)
                            typeToSearch = JavaEntities.simpleNameToType(table, classpath(), table.current().getQualifiedName(), receiver.get(0).toString());
                        //OBJECTS
                        else {
                            VariableT objectLookup = (VariableT) table.lookup(receiver.get(0).toString());
                            Type typetoSearch = objectLookup.getType();
                        }
                        List<Type> actuals = JavaEntities.typeList((List) dispatch(n.getNode(3)));
                        MethodT method =
                            JavaEntities.typeDotMethod(table, classpath(), typeToSearch, true, methodName, actuals);
                        n.setProperty("mangledName", methodScopeToMangledName.get(method.getScope()));
                    }

                    else if (receiver.getName().equals("CallExpression")) {
                        Type objectType = returnTypeFromCallExpression(receiver);
                        List<Type> actuals = JavaEntities.typeList((List) dispatch(n.getNode(3)));
                        MethodT method =
                            JavaEntities.typeDotMethod(table, classpath(), objectType, true, methodName, actuals);
                        n.setProperty("mangledName", methodScopeToMangledName.get(method.getScope()));
                    }

                    else if (receiver.getName().equals("ThisExpression")) {
                        Type currentType = JavaEntities.currentType(table);
                        List<Type> actuals = JavaEntities.typeList((List) dispatch(n.getNode(3)));
                        MethodT method =
                            JavaEntities.typeDotMethod(table, classpath(), currentType, true, methodName, actuals);
                        n.setProperty("mangledName", methodScopeToMangledName.get(method.getScope()));
                    }

                    else if (receiver.getName().equals("SuperExpression")) {
                        Type currentType = JavaEntities.currentType(table);
                        Type superType = JavaEntities.directSuperTypes(table, classpath(), currentType).get(0);
                        List<Type> actuals = JavaEntities.typeList((List) dispatch(n.getNode(3)));
                        MethodT method =
                            JavaEntities.typeDotMethod(table, classpath(), superType, true, methodName, actuals);
                        n.setProperty("mangledName", methodScopeToMangledName.get(method.getScope()));
                    }
                }
            }
        }



        public Node visitPrimaryIdentifier(GNode n) {
            String fieldName = n.getString(0);

            ClassOrInterfaceT typeToSearch = JavaEntities.currentType(table);
            if (typeToSearch == null) return n;

            VariableT field = null;
            SymbolTable.Scope oldScope = table.current();
            JavaEntities.enterScopeByQualifiedName(table, typeToSearch.getScope());
            for (final VariableT f : JavaEntities.fieldsOwnAndInherited(table, classpath(), typeToSearch))
                if (f.getName().equals(fieldName)) {
                    field = f;
                    break;
                }
            table.setScope(oldScope);

            if (field == null) return n;

            //explicit this access
            Type t = (Type) table.lookup(fieldName);
            if (t == null || !t.isVariable()) {
                t = field;
            }

            if (JavaEntities.isFieldT(t) && !TypeUtil.isStaticType(t)) {
                GNode n1 = GNode.create("SelectionExpression", makeThisExpression(), fieldName);
                TypeUtil.setType(n1, TypeUtil.getType(n));
                return n1;
            }

            return n;
        }



        public List<Type> visitArguments(final GNode n) {
            List<Type> result = new ArrayList<Type>(n.size());
            for (int i = 0; i < n.size(); i++) {
                GNode argi = n.getGeneric(i);
                Type ti = (Type) argi.getProperty(Constants.TYPE);
                if (ti.isVariable()) {
                    VariableT vi = ti.toVariable();
                    ti = vi.getType();
                }
                result.add(ti);
                Object argi1 = dispatch(argi);
                if (argi1 != null && argi1 instanceof Node) {
                    n.set(i, argi1);
                }
            }
            return result;
        }


        public void visit(GNode n) {
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
