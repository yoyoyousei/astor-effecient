package fr.inria.main.evolution;

import fastrepair.ExactIngredientStrategy;
import fr.inria.astor.approaches.jgenprog.JGenProg;
import fr.inria.astor.approaches.jgenprog.jGenProgSpace;
import fr.inria.astor.approaches.jkali.JKaliSpace;
import fr.inria.astor.approaches.mutRepair.MutRepairSpace;
import fr.inria.astor.core.entities.ProgramVariant;
import fr.inria.astor.core.loop.AstorCoreEngine;
import fr.inria.astor.core.loop.ExhaustiveSearchEngine;
import fr.inria.astor.core.loop.population.FitnessPopulationController;
import fr.inria.astor.core.loop.population.ProgramVariantFactory;
import fr.inria.astor.core.loop.spaces.ingredients.IngredientSearchStrategy;
import fr.inria.astor.core.loop.spaces.ingredients.IngredientSpace;
import fr.inria.astor.core.loop.spaces.ingredients.ingredientSearch.EfficientIngredientStrategy;
import fr.inria.astor.core.loop.spaces.ingredients.scopes.GlobalBasicIngredientSpace;
import fr.inria.astor.core.loop.spaces.ingredients.scopes.LocalIngredientSpace;
import fr.inria.astor.core.loop.spaces.ingredients.scopes.PackageBasicFixSpace;
import fr.inria.astor.core.loop.spaces.operators.AstorOperator;
import fr.inria.astor.core.loop.spaces.operators.OperatorSelectionStrategy;
import fr.inria.astor.core.loop.spaces.operators.OperatorSpace;
import fr.inria.astor.core.loop.spaces.operators.UniformRandomRepairOperatorSpace;
import fr.inria.astor.core.manipulation.MutationSupporter;
import fr.inria.astor.core.manipulation.filters.AbstractFixSpaceProcessor;
import fr.inria.astor.core.manipulation.filters.IFConditionFixSpaceProcessor;
import fr.inria.astor.core.manipulation.filters.SingleStatementFixSpaceProcessor;
import fr.inria.astor.core.setup.ConfigurationProperties;
import fr.inria.astor.core.setup.FinderTestCases;
import fr.inria.astor.core.setup.ProjectRepairFacade;
import fr.inria.astor.core.validation.validators.ProcessEvoSuiteValidator;
import fr.inria.astor.core.validation.validators.ProcessValidator;
import fr.inria.astor.core.validation.validators.ProgramValidator;
import fr.inria.main.AbstractMain;
import fr.inria.main.ExecutionMode;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.Logger;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by s-sumi on 16/08/22.
 */
public class MyAstorMain extends AbstractMain{
    protected Logger log = Logger.getLogger(AstorMain.class.getName());
    private String expRoot="Experiment/";
    private String expRes="tmp.txt";
    AstorCoreEngine astorCore = null;
    private String arg[];
    private PrintWriter pw;

    public void initProject(String location, String projectName, String dependencies, String packageToInstrument,
                            double thfl, String failing) throws Exception {

        List<String> failingList = Arrays.asList(failing.split(File.pathSeparator));
        String method = this.getClass().getSimpleName();
        projectFacade = getProject(location, projectName, method, failingList, dependencies, true);
        projectFacade.getProperties().setExperimentName(this.getClass().getSimpleName());

        projectFacade.setupWorkingDirectories(ProgramVariant.DEFAULT_ORIGINAL_VARIANT);

        FinderTestCases.findTestCasesForRegression(
                projectFacade.getOutDirWithPrefix(ProgramVariant.DEFAULT_ORIGINAL_VARIANT), projectFacade);

    }

    /**
     * It creates a repair engine according to an execution mode.
     *
     * @return
     * @throws Exception
     */

    public AstorCoreEngine createEngine(ExecutionMode mode) throws Exception {
        astorCore = null;
        MutationSupporter mutSupporter = new MutationSupporter();
        List<AbstractFixSpaceProcessor<?>> ingredientProcessors = new ArrayList<AbstractFixSpaceProcessor<?>>();
        // Fix Space
        ingredientProcessors.add(new SingleStatementFixSpaceProcessor());

        // We check if the user defines the operators to include in the operator space
        OperatorSpace operatorSpace = null;
        String customOp = ConfigurationProperties.getProperty("customop");
        if (customOp != null && !customOp.isEmpty()) {
            operatorSpace = createCustomOperatorSpace(customOp);
        }



        if (ExecutionMode.jKali.equals(mode)) {
            astorCore = new ExhaustiveSearchEngine(mutSupporter, projectFacade);
            if(operatorSpace == null)
                operatorSpace =  new JKaliSpace();
            ConfigurationProperties.properties.setProperty("regressionforfaultlocalization", "true");
            ConfigurationProperties.properties.setProperty("population", "1");

        } else if (ExecutionMode.jGenProg.equals(mode)) {
            astorCore = new JGenProg(mutSupporter, projectFacade);
            if(operatorSpace == null)
                operatorSpace = new jGenProgSpace();
            //We retrieve strategy for navigating operator space
            String opStrategyClassName = 	ConfigurationProperties.properties.getProperty("opselectionstrategy");
            if(opStrategyClassName != null){
                OperatorSelectionStrategy strategy =  createOperationSelectionStrategy(opStrategyClassName,operatorSpace);
                astorCore.setOperatorSelectionStrategy(strategy);
            }
            else{//By default, uniform strategy
                astorCore.setOperatorSelectionStrategy(new UniformRandomRepairOperatorSpace(operatorSpace));
            }
            // The ingredients for build the patches
            String scope = ConfigurationProperties.properties.getProperty("scope");
            IngredientSpace ingredientspace = null;
            if ("global".equals(scope)) {
                ingredientspace = (new GlobalBasicIngredientSpace(ingredientProcessors));
            } else if ("package".equals(scope)) {
                ingredientspace = (new PackageBasicFixSpace(ingredientProcessors));
            } else if ("local".equals(scope)) {
                ingredientspace = (new LocalIngredientSpace(ingredientProcessors));
            }else{
                ingredientspace = loadSpace(scope, ingredientProcessors);
            }

            IngredientSearchStrategy ingStrategy = retrieveIngredientStrategy(ingredientspace);

            ((JGenProg) astorCore).setIngredientStrategy(ingStrategy);

        } else if (ExecutionMode.MutRepair.equals(mode)) {
            astorCore = new ExhaustiveSearchEngine(mutSupporter, projectFacade);
            if(operatorSpace == null)
                operatorSpace = new MutRepairSpace();
            // ConfigurationProperties.properties.setProperty("stopfirst",
            // "false");
            ConfigurationProperties.properties.setProperty("regressionforfaultlocalization", "true");
            ConfigurationProperties.properties.setProperty("population", "1");
            ingredientProcessors.clear();
            ingredientProcessors.add(new IFConditionFixSpaceProcessor());
        } else {
            // If the execution mode is any of the predefined, Astor
            // interpretates as
            // a custom engine, where the value corresponds to the class name of
            // the engine class
            String customengine = ConfigurationProperties.getProperty("customengine");
            astorCore = createEngineFromArgument(customengine, mutSupporter, projectFacade);

        }

        // Now we define the commons properties

        if(operatorSpace != null){
            astorCore.setOperatorSpace(operatorSpace);
        }else{
            throw new Exception("The operator Space cannot be null");
        }


        // Pop controller
        astorCore.setPopulationControler(new FitnessPopulationController());
        //
        astorCore.setVariantFactory(new ProgramVariantFactory(ingredientProcessors));

        // We do the first validation using the standard validation (test suite
        // process)
        astorCore.setProgramValidator(new ProcessValidator());

        // Initialize Population
        astorCore.createInitialPopulation();

        // After initializing population, we set up specific validation
        // mechanism
        // Select the kind of validation of a variant.
        String validationArgument = ConfigurationProperties.properties.getProperty("validation");
        if (validationArgument.equals("evosuite")) {
            ProcessEvoSuiteValidator validator = new ProcessEvoSuiteValidator();
            astorCore.setProgramValidator(validator);
        } else
            // if validation is different to default (process)
            if (!validationArgument.equals("process")) {
                astorCore.setProgramValidator(createProcessValidatorFromArgument(validationArgument));
            }

        return astorCore;

    }

    /**
     * We create an instance of the Engine which name is passed as argument.
     *
     * @param customEngine
     * @param mutSupporter
     * @param projectFacade
     * @return
     * @throws Exception
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private AstorCoreEngine createEngineFromArgument(String customEngine, MutationSupporter mutSupporter,
                                                     ProjectRepairFacade projectFacade) throws Exception {
        Object object = null;
        try {
            Class classDefinition = Class.forName(customEngine);
            object = classDefinition.getConstructor(mutSupporter.getClass(), projectFacade.getClass())
                    .newInstance(mutSupporter, projectFacade);
        } catch (Exception e) {
            log.error("Loading custom engine: " + customEngine + " --" + e);
            throw new Exception("Error Loading Engine: " + e);
        }
        if (object instanceof AstorCoreEngine)
            return (AstorCoreEngine) object;
        else
            throw new Exception(
                    "The strategy " + customEngine + " does not extend from " + AstorCoreEngine.class.getName());

    }

    private ProgramValidator createProcessValidatorFromArgument(String className) throws Exception {
        Object object = null;
        try {
            Class classDefinition = Class.forName(className);
            object = classDefinition.newInstance();
        } catch (Exception e) {
            log.error("LoadingProcessValidator: " + className + " --" + e);
            throw new Exception("Error Loading Engine: " + e);
        }
        if (object instanceof ProgramValidator)
            return (ProgramValidator) object;
        else
            throw new Exception(
                    "The strategy " + className + " does not extend from " + ProgramValidator.class.getName());

    }

    private IngredientSearchStrategy retrieveIngredientStrategy(IngredientSpace ingredientspace) throws Exception {
        String strategy = ConfigurationProperties.getProperty("ingredientstrategy");
        IngredientSearchStrategy st = null;
        System.out.println("selecting strategy");
        if (strategy == null || strategy.trim().isEmpty()){
            //AstorCtIngredientSpace ctIngredientSpace = (AstorCtIngredientSpace) ingredientspace;
            st = new EfficientIngredientStrategy(ingredientspace);
        }
        else if(strategy.equals("exact")){
            System.out.println("use proposition strategy");
            st=new ExactIngredientStrategy(ingredientspace);
        }
        else{
            st = loadCustomIngredientStrategy(strategy, ingredientspace);
        }
        return st;
    }

    private OperatorSpace createCustomOperatorSpace(String customOp) throws Exception {
        OperatorSpace customSpace = new OperatorSpace();
        String[] operators = customOp.split(File.pathSeparator);
        for (String op : operators) {
            AstorOperator aop = createOperator(op);
            if (aop != null)
                customSpace.register(aop);
        }
        if (customSpace.getOperators().isEmpty()) {
            log.error("Empty custom operator space");
            throw new Exception("Empty custom operator space");
        }
        return customSpace;
    }

    private OperatorSelectionStrategy createOperationSelectionStrategy(String opSelectionStrategyClassName, OperatorSpace space) throws Exception{
        Object object = null;
        try {
            Class classDefinition = Class.forName(opSelectionStrategyClassName);
            object = classDefinition.getConstructor(OperatorSpace.class).newInstance(space);
        } catch (Exception e) {
            log.error("Loading strategy " + opSelectionStrategyClassName + " --" + e);
            throw new Exception("Loading strategy: " + e);
        }
        if (object instanceof OperatorSelectionStrategy)
            return (OperatorSelectionStrategy) object;
        else
            throw new Exception("The strategy " + opSelectionStrategyClassName + " does not extend from "
                    +  OperatorSelectionStrategy.class.getName());
    }

    AstorOperator createOperator(String className) {
        Object object = null;
        try {
            Class classDefinition = Class.forName(className);
            object = classDefinition.newInstance();
        } catch (Exception e) {
            log.error(e);
        }
        if (object instanceof AstorOperator)
            return (AstorOperator) object;
        else
            log.error("The operator " + className + " does not extend from " + AstorOperator.class.getName());
        return null;
    }

    /**
     * Load a custom ing strategy using reflection.
     *
     * @param customStrategyclassName
     * @param ingredientSpace
     * @return
     * @throws Exception
     */
    private IngredientSearchStrategy loadCustomIngredientStrategy(String customStrategyclassName,
                                                                  IngredientSpace ingredientSpace) throws Exception {
        Object object = null;
        try {
            Class classDefinition = Class.forName(customStrategyclassName);
            object = classDefinition.getConstructor(IngredientSpace.class).newInstance(ingredientSpace);
        } catch (Exception e) {
            log.error("Loading strategy " + customStrategyclassName + " --" + e);
            throw new Exception("Loading strategy: " + e);
        }
        if (object instanceof IngredientSearchStrategy)
            return (IngredientSearchStrategy) object;
        else
            throw new Exception("The strategy " + customStrategyclassName + " does not extend from "
                    +  IngredientSearchStrategy.class.getName());

    }

    private IngredientSpace loadSpace(String customSpaceclassName,
                                      List<AbstractFixSpaceProcessor<?>> ingredientProcessors) throws Exception {
        Object object = null;
        try {
            Class classDefinition = Class.forName(customSpaceclassName);
            object = classDefinition.getConstructor(List.class).
                    newInstance(ingredientProcessors);
        } catch (Exception e) {
            log.error("Loading strategy " + customSpaceclassName + " --" + e);
            throw new Exception("Loading strategy: " + e);
        }
        if (object instanceof IngredientSpace)
            return (IngredientSpace) object;
        else
            throw new Exception("The strategy " +customSpaceclassName+ " does not extend from "
                    +  IngredientSpace.class.getName());

    }

    @Override
    public void run(String location, String projectName, String dependencies, String packageToInstrument, double thfl,
                    String failing) throws Exception {

        long startT = System.currentTimeMillis();
        initProject(location, projectName, dependencies, packageToInstrument, thfl, failing);

        String mode = ConfigurationProperties.getProperty("mode");

        if ("statement".equals(mode) || "jgenprog".equals(mode))
            astorCore = createEngine(ExecutionMode.jGenProg);
        else if ("statement-remove".equals(mode) || "jkali".equals(mode))
            astorCore = createEngine(ExecutionMode.jKali);
        else if ("mutation".equals(mode) || "jmutrepair".equals(mode))
            astorCore = createEngine(ExecutionMode.MutRepair);
        else if ("custom".equals(mode))
            astorCore = createEngine(ExecutionMode.custom);
        else {
            System.err.println("Unknown mode of execution: '" + mode
                    + "', know modes are: jgenprog, jkali, jmutrepair or custom.");
            return;
        }
        ConfigurationProperties.print();

        astorCore.startEvolution();
        this.pw=new PrintWriter(new BufferedWriter( new FileWriter( new File(this.expRoot+this.expRes),true)));
        pw.write(ConfigurationProperties.getProperty("location"));              pw.write(",");
        pw.write(ConfigurationProperties.getProperty("ingredientstrategy")==null ?
                "normal" : ConfigurationProperties.getProperty("ingredientstrategy"));    pw.write(",");
        pw.write(ConfigurationProperties.getProperty("scope"));                 pw.write(",");
        pw.write(ConfigurationProperties.getProperty("seed"));                  pw.write(",");
        astorCore.showResults(pw);
        pw.close();
        long endT = System.currentTimeMillis();
        log.info("Time Total(s): " + (endT - startT) / 1000d);
    }

    /**
     * @param args
     * @throws Exception
     * @throws ParseException
     */
    public static void main(String[] args) throws Exception {
        List<String> tmp;
        String[] array;
        Integer bugsRepaired[]=     //{2,28,40,49,5,50,53,56,60,70,71,73,78,8,80,81,82,84,85,95};
                                    {49,5,70,73,95};
        //for (int i = 1; i < 107; i++) {
        for(Integer i:bugsRepaired){
            List<String> location=new ArrayList<>(Arrays.asList(("-location Experiment/math_"+String.valueOf(i)+"_buggy").split(" ")));
            String bugInfo=executeCommand(("defects4j info -p Math -b "+String.valueOf(i)).split(" "));
            Matcher preMatch= Pattern.compile("Root cause in triggering tests:(.|\n)+List of modified sources:").matcher(bugInfo);
            if(preMatch.find()) {
                List<String> failed=new ArrayList<>();
                Matcher failedTestCases=Pattern.compile("\\s-\\s.+::").matcher(preMatch.group());
                while (failedTestCases.find()){
                    failed.add(failedTestCases.group().replace("::","").replace(" - ",""));
                }
                if(failed.size()!=0) {
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < failed.size(); j++) {
                        sb.append(failed.get(j));
                        if (j != failed.size() - 1) sb.append(":");
                    }
                    List<String> finalFailed = new ArrayList<>();
                    finalFailed.add("-failing");
                    finalFailed.add(sb.toString());
                    for (int j = 0; j < 10; j++) {
                        tmp = new ArrayList<>(Arrays.asList(args));    //初期化
                        tmp.addAll(location);
                        tmp.addAll(finalFailed);

                        tmp.add("-seed");           //シードせってい
                        tmp.add(String.valueOf(j));

                        array = new String[tmp.size()];
                        tmp.toArray(array);

                        MyAstorMain m = new MyAstorMain();
                        m.execute(array);

                        m = new MyAstorMain();
                        tmp.add("-ingredientstrategy"); //予測付きに切り替え
                        tmp.add("exact");
                        array = new String[tmp.size()];
                        tmp.toArray(array);
                        m.execute(array);
                    }
                }else{
                    System.err.println("ほげほげほげえええええええええええええええええええ");
                }
            }else{
                System.err.println("ほげええええええええええええええええええええええええええええええ");
            }
        }
    }

    public void execute(String[] args) throws Exception {
        this.arg=args;

        boolean correct = processArguments(args);
        if (!correct) {
            System.err.println("Problems with commands arguments");
            return;
        }
        if (isExample(args)) {
            executeExample(args);
            return;
        }

        String dependencies = ConfigurationProperties.getProperty("dependenciespath");
        String failing = ConfigurationProperties.getProperty("failing");
        String location = ConfigurationProperties.getProperty("location");
        String packageToInstrument = ConfigurationProperties.getProperty("packageToInstrument");
        double thfl = ConfigurationProperties.getPropertyDouble("flthreshold");
        String projectName = ConfigurationProperties.getProperty("projectIdentifier");

        run(location, projectName, dependencies, packageToInstrument, thfl, failing);

    }

    public AstorCoreEngine getEngine() {
        return astorCore;
    }

    public static String executeCommand(String[] cmd){

        StringBuilder sb=new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);//usable spaced string or array

            Process p = pb.start();
            InputStream is = p.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String line = null;
            while ((line = br.readLine()) != null) {
                sb.append(line);sb.append("\n");
            }
            int r = p.waitFor(); // Let the process finish.
        } catch (Exception e) {
            throw new RuntimeException();
        }
        return sb.toString();
    }

}