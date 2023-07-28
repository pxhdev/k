// Copyright (c) Runtime Verification, Inc. All Rights Reserved.
package org.kframework.backend.kore;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.SetMultimap;
import org.kframework.Collections;
import org.kframework.attributes.Att;
import org.kframework.attributes.HasLocation;
import org.kframework.attributes.Source;
import org.kframework.builtin.BooleanUtils;
import org.kframework.builtin.KLabels;
import org.kframework.builtin.Sorts;
import org.kframework.compile.AddSortInjections;
import org.kframework.compile.ExpandMacros;
import org.kframework.compile.RefreshRules;
import org.kframework.compile.RewriteToTop;
import org.kframework.definition.Claim;
import org.kframework.definition.Module;
import org.kframework.definition.NonTerminal;
import org.kframework.definition.Production;
import org.kframework.definition.Rule;
import org.kframework.definition.RuleOrClaim;
import org.kframework.definition.Sentence;
import org.kframework.kompile.KompileOptions;
import org.kframework.kore.InjectedKLabel;
import org.kframework.kore.K;
import org.kframework.kore.KApply;
import org.kframework.kore.KAs;
import org.kframework.kore.KLabel;
import org.kframework.kore.KList;
import org.kframework.kore.KORE;
import org.kframework.kore.KRewrite;
import org.kframework.kore.KSequence;
import org.kframework.kore.KToken;
import org.kframework.kore.KVariable;
import org.kframework.kore.Sort;
import org.kframework.kore.SortHead;
import org.kframework.kore.VisitK;
import org.kframework.utils.StringUtil;
import org.kframework.utils.errorsystem.KEMException;
import org.kframework.utils.errorsystem.KException;
import org.kframework.utils.errorsystem.KExceptionManager;
import scala.Option;
import scala.Tuple2;
import scala.collection.Seq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.kframework.Collections.*;
import static org.kframework.kore.KORE.*;

public class ModuleToKORE {
    public enum SentenceType {
        REWRITE_RULE,
        ONE_PATH,
        ALL_PATH
    }

    public static final String ONE_PATH_OP = KLabels.RL_wEF.name();
    public static final String ALL_PATH_OP = KLabels.RL_wAF.name();
    private final Module module;
    private final AddSortInjections addSortInjections;
    private final KLabel topCellInitializer;
    private final Set<String> mlBinders = new HashSet<>();
    private final KompileOptions options;

    private final KExceptionManager kem;

    public ModuleToKORE(Module module, KLabel topCellInitializer, KompileOptions options) {
        this(module, topCellInitializer, options, null);
    }

    public ModuleToKORE(Module module, KLabel topCellInitializer, KompileOptions options, KExceptionManager kem) {
        this.kem = kem;
        this.module = module;
        this.addSortInjections = new AddSortInjections(module);
        this.topCellInitializer = topCellInitializer;
        this.options = options;
        for (Production prod : iterable(module.sortedProductions())) {
            if (prod.att().contains(Att.ML_BINDER())) {
                mlBinders.add(prod.klabel().get().name());
            }
        }
    }
    private static final boolean METAVAR = false;

    public void convert(boolean heatCoolEq, String prelude, StringBuilder semantics, StringBuilder syntax, StringBuilder macros) {
        Sort topCellSort = Sorts.GeneratedTopCell();
        String topCellSortStr = getSortStr(topCellSort);
        semantics.append("[topCellInitializer{}(");
        convert(topCellInitializer, semantics);
        semantics.append("()), ");
        StringBuilder sb = new StringBuilder();
        module.addAttToAttributesMap(Att.SOURCE(), true);
        // insert the location of the main module so the backend can provide better error location
        convert(Att.empty().add(Source.class, module.att().get(Source.class)), sb, null, null);
        semantics.append(sb.subSequence(1, sb.length() - 1));
        semantics.append("]\n\n");

        semantics.append(prelude);
        semantics.append("\n");

        SentenceType sentenceType = getSentenceType(module.att()).orElse(SentenceType.REWRITE_RULE);
        semantics.append("module ");
        convert(module.name(), semantics);
        semantics.append("\n\n// imports\n");
        semantics.append("  import K []\n\n// sorts\n");

        Map<Integer, String> priorityToPreviousGroup = new HashMap<>();
        List<Integer> priorityList = new ArrayList<>(module.getRulePriorities());
        java.util.Collections.sort(priorityList);
        if (priorityList.size() > 0 ) {
            priorityToPreviousGroup.put(priorityList.get(0), "");
        }
        for (int i = 1; i < priorityList.size(); i++) {
            Integer previous = priorityList.get(i - 1);
            Integer current = priorityList.get(i);
            priorityToPreviousGroup.put(current, String.format("priorityLE%d", previous));
        }

        Set<String> collectionSorts = new HashSet<>();
        collectionSorts.add("SET.Set");
        collectionSorts.add("MAP.Map");
        collectionSorts.add("LIST.List");
        collectionSorts.add("ARRAY.Array");
        collectionSorts.add("RANGEMAP.RangeMap");
        module.removeAttFromAttributesMap(Att.HAS_DOMAIN_VALUES());
        if (module.hasAttributesMap().contains(Att.TOKEN())) {
            module.addAttToAttributesMap(Att.HAS_DOMAIN_VALUES(), false);
        }
        translateSorts(collectionSorts, semantics);

        SetMultimap<KLabel, Rule> functionRules = module.getFunctionRules();

        semantics.append("\n// symbols\n");
        Set<Production> overloads = new HashSet<>();
        for (Production lesser : iterable(module.overloads().elements())) {
            for (Production greater : iterable(module.overloads().relations().get(lesser).getOrElse(Collections::<Production>Set))) {
                overloads.add(greater);
            }
        }
        translateSymbols(semantics);

        // print syntax definition
        syntax.append(semantics);
        for (Tuple2<Sort, scala.collection.immutable.List<Production>> sort : iterable(module.bracketProductionsFor())) {
            for (Production prod : iterable(sort._2())) {
                translateSymbol(prod.att().get(Att.BRACKET_LABEL(), KLabel.class), prod, syntax);
            }
        }
        for (Production prod : iterable(module.sortedProductions())) {
            if (isBuiltinProduction(prod)) {
                continue;
            }
            if (prod.isSubsort() && !prod.sort().equals(Sorts.K())) {
                genSubsortAxiom(prod, syntax);
                continue;
            }
        }

        for (Production lesser : iterable(module.overloads().elements())) {
            for (Production greater : iterable(module.overloads().relations().get(lesser).getOrElse(() -> Collections.<Production>Set()))) {
                genOverloadedAxiom(lesser, greater, syntax);
            }
        }

        syntax.append("endmodule []\n");

        semantics.append("\n// generated axioms\n");
        Set<Tuple2<Production, Production>> noConfusion = new HashSet<>();
        for (Production prod : iterable(module.sortedProductions())) {
            if (isBuiltinProduction(prod)) {
                continue;
            }
            if (prod.isSubsort() && !prod.sort().equals(Sorts.K())) {
                genSubsortAxiom(prod, semantics);
                continue;
            }
            if (prod.klabel().isEmpty()) {
                continue;
            }
            if (prod.att().contains(Att.ASSOC())) {
                genAssocAxiom(prod, semantics);
            }
            //if (prod.att().contains(Att.COMM())) {
            //    genCommAxiom(prod, semantics);
            //}
            if (prod.att().contains(Att.IDEM())) {
                genIdemAxiom(prod, semantics);
            }
            if (isFunction(prod) && prod.att().contains(Att.UNIT())) {
                genUnitAxiom(prod, semantics);
            }
            if (prod.att().contains(Att.FUNCTIONAL())) {
                genFunctionalAxiom(prod, semantics);
            }
            if (prod.att().contains(Att.CONSTRUCTOR())) {
                genNoConfusionAxioms(prod, noConfusion, semantics);
            }
        }

        for (Sort sort : iterable(module.sortedAllSorts())) {
            genNoJunkAxiom(sort, semantics);
        }

        for (Production lesser : iterable(module.overloads().elements())) {
            for (Production greater : iterable(module.overloads().relations().get(lesser).getOrElse(() -> Collections.<Production>Set()))) {
                genOverloadedAxiom(lesser, greater, semantics);
            }
        }

        semantics.append("\n// rules\n");

        macros.append("// macros\n");
        int ruleIndex = 0;
        ListMultimap<Integer, String> priorityToAlias = ArrayListMultimap.create();
        for (Rule rule : iterable(module.sortedRules())) {
            if (ExpandMacros.isMacro(rule)) {
                convertRule(rule, ruleIndex, heatCoolEq, topCellSortStr, functionRules,
                        priorityToPreviousGroup, priorityToAlias, sentenceType, macros);
            } else {
                convertRule(rule, ruleIndex, heatCoolEq, topCellSortStr, functionRules,
                        priorityToPreviousGroup, priorityToAlias, sentenceType, semantics);
            }
            ruleIndex++;
        }

        if (options.enableKoreAntileft) {
            semantics.append("\n// priority groups\n");
            genPriorityGroups(priorityList, priorityToPreviousGroup, priorityToAlias, topCellSortStr, semantics);
        }

        semantics.append("endmodule ");
        convert(module.att().remove(Att.DIGEST()), semantics, null, null);
        semantics.append("\n");
    }

    private void translateSorts(Set<String> collectionSorts, StringBuilder sb) {
        for (SortHead sort : iterable(module.sortedDefinedSorts())) {
            if (sort.equals(Sorts.K().head()) || sort.equals(Sorts.KItem().head())) {
                continue;
            }
            sb.append("  ");
            Att att = module.sortAttributesFor().get(sort).getOrElse(() -> KORE.Att());
            if (att.contains(Att.HOOK())) {
                if (collectionSorts.contains(att.get(Att.HOOK()))) {
                    if (att.get(Att.HOOK()).equals("ARRAY.Array")) {
                        att = att.remove(Att.ELEMENT());
                        att = att.remove(Att.UNIT());
                        att = att.remove(Att.HOOK());
                    } else {
                        Production concatProd = stream(module.productionsForSort().apply(sort)).filter(p -> p.att().contains(Att.ELEMENT())).findAny().get();
                        att = att.add(Att.ELEMENT(), K.class, KApply(KLabel(concatProd.att().get(Att.ELEMENT()))));
                        att = att.add(Att.CONCAT(), K.class, KApply(concatProd.klabel().get()));
                        att = att.add(Att.UNIT(), K.class, KApply(KLabel(concatProd.att().get(Att.UNIT()))));
                        sb.append("hooked-");
                    }
                } else {
                    sb.append("hooked-");
                }
            }
            att = att.remove(Att.HAS_DOMAIN_VALUES());
            if (module.getTokenSorts().contains(sort)) {
                att = att.add(Att.HAS_DOMAIN_VALUES());
            }
            if (sort.params() == 0 && Sort(sort).isNat()) {
              att = att.add(Att.NAT(), sort.name());
            }
            sb.append("sort ");
            convert(sort, sb);
            sb.append(" ");
            convert(att, sb, null, null);
            sb.append("\n");
        }
    }

    private void translateSymbols(StringBuilder sb) {
        for (Production prod : iterable(module.sortedProductions())) {
            if (isBuiltinProduction(prod)) {
                continue;
            }
            if (prod.klabel().isEmpty()) {
                continue;
            }
            translateSymbol(prod.klabel().get(), prod, sb);
        }
    }

    private void translateSymbol(KLabel label, Production prod, StringBuilder sb) {
        sb.append("  ");
        if (isFunction(prod) && prod.att().contains(Att.HOOK()) && module.isRealHook(prod.att(), immutable(options.hookNamespaces))) {
            sb.append("hooked-");
        }
        sb.append("symbol ");
        convert(label, prod.params(), sb);
        String conn;
        sb.append("(");
        conn = "";
        for (NonTerminal nt : iterable(prod.nonterminals())) {
            Sort sort = nt.sort();
            sb.append(conn);
            convert(sort, prod, sb);
            conn = ", ";
        }
        sb.append(") : ");
        convert(prod.sort(), prod, sb);
        sb.append(" ");
        convert(prod.att(), sb, null, null);
        sb.append("\n");
    }


    private void genSubsortAxiom(Production prod, StringBuilder sb) {
        Production finalProd = prod;
        functionalPattern(prod, () -> {
            sb.append("inj{");
            convert(finalProd.getSubsortSort(), finalProd, sb);
            sb.append(", ");
            convert(finalProd.sort(), finalProd, sb);
            sb.append("} (From:");
            convert(finalProd.getSubsortSort(), finalProd, sb);
            sb.append(")");
        }, sb);
        sb.append(" [subsort{");
        convert(prod.getSubsortSort(), prod, sb);
        sb.append(", ");
        convert(prod.sort(), prod, sb);
        sb.append("}()] // subsort\n");
    }

    private void genAssocAxiom(Production prod, StringBuilder sb) {
        // s(s(K1,K2),K3) = s(K1,s(K2,K3))
        if (prod.arity() != 2) {
            throw KEMException.compilerError("Found a non-binary production with the assoc attribute", prod);
        }
        if (!(module.subsorts().lessThanEq(prod.sort(), prod.nonterminal(0).sort()) &&
                module.subsorts().lessThanEq(prod.sort(), prod.nonterminal(1).sort()))) {
            throw KEMException.compilerError("Found an associative production with ill formed sorts", prod);
        }
        sb.append("  axiom");
        convertParams(prod.klabel(), true, sb);
        sb.append(" \\equals{");
        convert(prod.sort(), prod, sb);
        sb.append(", R} (");
        convert(prod.klabel().get(), prod, sb);
        sb.append("(");
        convert(prod.klabel().get(), prod, sb);
        sb.append("(K1:");
        convert(prod.sort(), prod, sb);
        sb.append(",K2:");
        convert(prod.sort(), prod, sb);
        sb.append("),K3:");
        convert(prod.sort(), prod, sb);
        sb.append("),");
        convert(prod.klabel().get(), prod, sb);
        sb.append("(K1:");
        convert(prod.sort(), prod, sb);
        sb.append(",");
        convert(prod.klabel().get(), prod, sb);
        sb.append("(K2:");
        convert(prod.sort(), prod, sb);
        sb.append(",K3:");
        convert(prod.sort(), prod, sb);
        sb.append("))) [assoc{}()] // associativity\n");
    }

    private void genCommAxiom(Production prod, StringBuilder sb) {
        // s(K1, K2) = s(K2, K1)
        if (prod.arity() != 2) {
            throw KEMException.compilerError("Found a non-binary production with the comm attribute", prod);
        }
        if (!(prod.nonterminal(0).sort().equals(prod.nonterminal(1).sort()))) {
            throw KEMException.compilerError("Found a commutative production with ill formed sorts", prod);
        }
        Sort childSort = prod.nonterminal(0).sort();
        sb.append("  axiom");
        convertParams(prod.klabel(), true, sb);
        sb.append(" \\equals{");
        convert(prod.sort(), prod, sb);
        sb.append(", R} (");
        convert(prod.klabel().get(), prod, sb);
        sb.append("(K1:");
        convert(childSort, prod, sb);
        sb.append(",K2:");
        convert(childSort, prod, sb);
        sb.append("),");
        convert(prod.klabel().get(), prod, sb);
        sb.append("(K2:");
        convert(childSort, prod, sb);
        sb.append(",K1:");
        convert(childSort, prod, sb);
        sb.append(")) [comm{}()] // commutativity\n");
    }

    private void genIdemAxiom(Production prod, StringBuilder sb) {
        // s(K, K) = K
        if (prod.arity() != 2) {
            throw KEMException.compilerError("Found a non-binary production with the assoc attribute", prod);
        }
        if (!(prod.sort().equals(prod.nonterminal(0).sort()) && prod.sort().equals(prod.nonterminal(1).sort()))) {
            throw KEMException.compilerError("Found an associative production with ill formed sorts", prod);
        }
        sb.append("  axiom");
        convertParams(prod.klabel(), true, sb);
        sb.append(" \\equals{");
        convert(prod.sort(), prod, sb);
        sb.append(", R} (");
        convert(prod.klabel().get(), prod, sb);
        sb.append("(K:");
        convert(prod.sort(), prod, sb);
        sb.append(",K:");
        convert(prod.sort(), prod, sb);
        sb.append("),K:");
        convert(prod.sort(), prod, sb);
        sb.append(") [idem{}()] // idempotency\n");
    }

    private void genUnitAxiom(Production prod, StringBuilder sb) {
        // s(K, unit) = K
        // s(unit, K) = K
        if (prod.arity() != 2) {
            throw KEMException.compilerError("Found a non-binary production with the assoc attribute", prod);
        }
        if (!(prod.sort().equals(prod.nonterminal(0).sort()) && prod.sort().equals(prod.nonterminal(1).sort()))) {
            throw KEMException.compilerError("Found an associative production with ill formed sorts", prod);
        }
        KLabel unit = KLabel(prod.att().get(Att.UNIT()));
        sb.append("  axiom");
        convertParams(prod.klabel(), true, sb);
        sb.append("\\equals{");
        convert(prod.sort(), prod, sb);
        sb.append(", R} (");
        convert(prod.klabel().get(), prod, sb);
        sb.append("(K:");
        convert(prod.sort(), prod, sb);
        sb.append(",");
        convert(unit, sb);
        sb.append("()),K:");
        convert(prod.sort(), prod, sb);
        sb.append(") [unit{}()] // right unit\n");

        sb.append("  axiom");
        convertParams(prod.klabel(), true, sb);
        sb.append("\\equals{");
        convert(prod.sort(), prod, sb);
        sb.append(", R} (");
        convert(prod.klabel().get(), prod, sb);
        sb.append("(");
        convert(unit, sb);
        sb.append("(),K:");
        convert(prod.sort(), prod, sb);
        sb.append("),K:");
        convert(prod.sort(), prod, sb);
        sb.append(") [unit{}()] // left unit\n");
    }

    private void genFunctionalAxiom(Production prod, StringBuilder sb) {
        // exists y . f(...) = y
        Production finalProd = prod;
        functionalPattern(prod, () -> applyPattern(finalProd, "K", sb), sb);
        sb.append(" [functional{}()] // functional\n");
    }

    private void genNoConfusionAxioms(Production prod, Set<Tuple2<Production, Production>> noConfusion, StringBuilder sb) {
        // c(x1,x2,...) /\ c(y1,y2,...) -> c(x1/\y2,x2/\y2,...)
        if (prod.arity() > 0) {
            sb.append("  axiom");
            convertParams(prod.klabel(), false, sb);
            sb.append("\\implies{");
            convert(prod.sort(), prod, sb);
            sb.append("} (\\and{");
            convert(prod.sort(), prod, sb);
            sb.append("} (");
            applyPattern(prod, "X", sb);
            sb.append(", ");
            applyPattern(prod, "Y", sb);
            sb.append("), ");
            convert(prod.klabel().get(), prod, sb);
            sb.append("(");
            String conn = "";
            for (int i = 0; i < prod.arity(); i++) {
                sb.append(conn);
                sb.append("\\and{");
                convert(prod.nonterminal(i).sort(), prod, sb);
                sb.append("} (X").append(i).append(":");
                convert(prod.nonterminal(i).sort(), prod, sb);
                sb.append(", Y").append(i).append(":");
                convert(prod.nonterminal(i).sort(), prod, sb);
                sb.append(")");
                conn = ", ";
            }
            sb.append(")) [constructor{}()] // no confusion same constructor\n");
        }
        for (Production prod2 : iterable(module.productionsForSort().apply(prod.sort().head()).toSeq().sorted(Production.ord()))) {
            // !(cx(x1,x2,...) /\ cy(y1,y2,...))
            if (prod2.klabel().isEmpty() || noConfusion.contains(Tuple2.apply(prod, prod2)) || prod.equals(prod2)
                    || !prod2.att().contains(Att.CONSTRUCTOR()) || isBuiltinProduction(prod2)) {
                // TODO (traiansf): add no confusion axioms for constructor vs inj.
                continue;
            }
            noConfusion.add(Tuple2.apply(prod, prod2));
            noConfusion.add(Tuple2.apply(prod2, prod));
            sb.append("  axiom");
            convertParams(prod.klabel(), false, sb);
            sb.append("\\not{");
            convert(prod.sort(), prod, sb);
            sb.append("} (\\and{");
            convert(prod.sort(), prod, sb);
            sb.append("} (");
            applyPattern(prod, "X", sb);
            sb.append(", ");
            applyPattern(prod2, "Y", sb);
            sb.append(")) [constructor{}()] // no confusion different constructors\n");
        }
    }

    public static int getPriority(Att att) {
        if (att.contains(Att.PRIORITY())) {
            try {
                return Integer.parseInt(att.get(Att.PRIORITY()));
            } catch (NumberFormatException e) {
                throw KEMException.compilerError("Invalid value for priority attribute: " + att.get(Att.PRIORITY()) + ". Must be an integer.", e);
            }
        } else if (att.contains(Att.OWISE())) {
            return 200;
        }
        return 50;
    }

    private void genNoJunkAxiom(Sort sort, StringBuilder sb) {
        StringBuilder sbTemp = new StringBuilder();
        sbTemp.append("  axiom{} ");
        boolean hasToken = false;
        int numTerms = 0;
        for (Production prod : iterable(mutable(module.productionsForSort()).getOrDefault(sort.head(), Set()).toSeq().sorted(Production.ord()))) {
            if (isFunction(prod) || prod.isSubsort() || isBuiltinProduction(prod)) {
                continue;
            }
            if (prod.klabel().isEmpty() && !((prod.att().contains(Att.TOKEN()) && !hasToken) || prod.isSubsort())) {
                continue;
            }
            numTerms++;
            sbTemp.append("\\or{");
            convert(sort, sbTemp);
            sbTemp.append("} (");
            if (prod.att().contains(Att.TOKEN()) && !hasToken) {
                convertTokenProd(sort, sbTemp);
                hasToken = true;
            } else if (prod.klabel().isDefined()) {
                for (int i = 0; i < prod.arity(); i++) {
                    sbTemp.append("\\exists{");
                    convert(sort, sbTemp);
                    sbTemp.append("} (X").append(i).append(":");
                    convert(prod.nonterminal(i).sort(), prod, sbTemp);
                    sbTemp.append(", ");
                }
                convert(prod.klabel().get(), prod, sbTemp);
                sbTemp.append("(");
                String conn = "";
                for (int i = 0; i < prod.arity(); i++) {
                    sbTemp.append(conn).append("X").append(i).append(":");
                    convert(prod.nonterminal(i).sort(), prod, sbTemp);
                    conn = ", ";
                }
                sbTemp.append(")");
                for (int i = 0; i < prod.arity(); i++) {
                    sbTemp.append(")");
                }
            }
            sbTemp.append(", ");
        }
        for (Sort s : iterable(module.sortedAllSorts())) {
            if (module.subsorts().lessThan(s, sort) && !sort.equals(Sorts.K())) {
                numTerms++;
                sbTemp.append("\\or{");
                convert(sort, sbTemp);
                sbTemp.append("} (");
                sbTemp.append("\\exists{");
                convert(sort, sbTemp);
                sbTemp.append("} (Val:");
                convert(s, sbTemp);
                sbTemp.append(", inj{");
                convert(s, sbTemp);
                sbTemp.append(", ");
                convert(sort, sbTemp);
                sbTemp.append("} (Val:");
                convert(s, sbTemp);
                sbTemp.append("))");
                sbTemp.append(", ");
            }
        }
        Att sortAtt = module.sortAttributesFor().get(sort.head()).getOrElse(() -> KORE.Att());
        if (!hasToken && sortAtt.contains(Att.TOKEN())) {
            numTerms++;
            sbTemp.append("\\or{");
            convert(sort, sbTemp);
            sbTemp.append("} (");
            convertTokenProd(sort, sbTemp);
            sbTemp.append(", ");
            hasToken = true;
        }
        sbTemp.append("\\bottom{");
        convert(sort, sbTemp);
        sbTemp.append("}()");
        for (int i = 0; i < numTerms; i++) {
            sbTemp.append(")");
        }
        sbTemp.append(" [constructor{}()] // no junk");
        if (hasToken && !METAVAR) {
            sbTemp.append(" (TODO: fix bug with \\dv)");
        }
        sbTemp.append("\n");

        // If there are no terms, then we don't need to generate the axiom.
        if (numTerms != 0) {
            sb.append(sbTemp);
        }
    }

    private void genOverloadedAxiom(Production lesser, Production greater, StringBuilder sb) {
        sb.append("  axiom{R} \\equals{");
        convert(greater.sort(), greater, sb);
        sb.append(", R} (");
        convert(greater.klabel().get(), greater, sb);
        sb.append("(");
        String conn = "";
        for (int i = 0; i < greater.nonterminals().size(); i++) {
            sb.append(conn);
            if (greater.nonterminal(i).sort().equals(lesser.nonterminal(i).sort())) {
                sb.append("K").append(i).append(":");
                convert(greater.nonterminal(i).sort(), greater, sb);
            } else {
                sb.append("inj{");
                convert(lesser.nonterminal(i).sort(), lesser, sb);
                sb.append(", ");
                convert(greater.nonterminal(i).sort(), greater, sb);
                sb.append("} (K").append(i).append(":");
                convert(lesser.nonterminal(i).sort(), lesser, sb);
                sb.append(")");
            }
            conn = ",";
        }
        sb.append("), inj{");
        convert(lesser.sort(), lesser, sb);
        sb.append(", ");
        convert(greater.sort(), greater, sb);
        sb.append("} (");
        convert(lesser.klabel().get(), lesser, sb);
        sb.append("(");
        conn = "";
        for (int i = 0; i < lesser.nonterminals().size(); i++) {
            sb.append(conn);
            sb.append("K").append(i).append(":");
            convert(lesser.nonterminal(i).sort(), lesser, sb);
            conn = ",";
        }
        sb.append("))) [overload{}(");
        convert(greater.klabel().get(), greater, sb);
        sb.append("(), ");
        convert(lesser.klabel().get(), lesser, sb);
        sb.append("())] // overloaded production\n");
    }

    private static boolean isBuiltinProduction(Production prod) {
        return prod.klabel().nonEmpty() && ConstructorChecks.isBuiltinLabel(prod.klabel().get());
    }

    public String convertSpecificationModule(Module definition, Module spec, SentenceType defaultSentenceType, StringBuilder sb) {
        SentenceType sentenceType = getSentenceType(spec.att()).orElse(defaultSentenceType);
        sb.setLength(0); // reset string writer
        Sort topCellSort = Sorts.GeneratedTopCell();
        String topCellSortStr = getSortStr(topCellSort);
        module.addAttToAttributesMap(Att.SOURCE(), true);
        convert(Att.empty().add(Source.class, spec.att().get(Source.class)), sb, null, null);
        module.clearAttributesMap();
        sb.append("\n");
        sb.append("module ");
        convert(spec.name(), sb);
        sb.append("\n\n// imports\n");
        sb.append("import ");
        convert(definition.name(), sb);
        sb.append(" []\n");
        sb.append("\n\n// claims\n");

        // We can replace the attributes here as we already generated the KORE definition and saved it to `definition.kore`
        module.clearAttributesMap();

        module.addAttToAttributesMap(Att.PRIORITY(), true);
        module.addAttToAttributesMap(Att.LABEL(), true);
        module.addAttToAttributesMap(Att.GROUP(), true);
        module.addAttToAttributesMap(Att.SOURCE(), true);
        module.addAttToAttributesMap(Att.LOCATION(), true);
        module.addAttToAttributesMap(Att.UNIQUE_ID(), true);

        for (Sentence sentence : iterable(spec.sentencesExcept(definition))) {
            if (sentence instanceof Claim || (sentence instanceof Rule && sentence.att().contains(Att.SIMPLIFICATION()))) {
                convertRule((RuleOrClaim) sentence, 0, false, topCellSortStr,
                        HashMultimap.create(), new HashMap<>(), ArrayListMultimap.create(), sentenceType, sb);
            }
        }
        sb.append("endmodule ");
        convert(spec.att().remove(Att.DIGEST()), sb, null, null);
        sb.append("\n");
        return sb.toString();
    }

    private Optional<SentenceType> getSentenceType(Att att) {
        if (att.contains(Att.ONE_PATH())) {
            return Optional.of(SentenceType.ONE_PATH);
        } else if (att.contains(Att.ALL_PATH())) {
            return Optional.of(SentenceType.ALL_PATH);
        }
        return Optional.empty();
    }

    private void convertRule(RuleOrClaim rule, int ruleIndex, boolean heatCoolEq, String topCellSortStr, SetMultimap<KLabel, Rule> functionRules,
                             Map<Integer, String> priorityToPreviousGroup,
                             ListMultimap<Integer, String> priorityToAlias,
                             SentenceType defaultSentenceType, StringBuilder sb) {
        SentenceType sentenceType = getSentenceType(rule.att()).orElse(defaultSentenceType);
        // injections should already be present, but this is an ugly hack to get around the
        // cache persistence issue that means that Sort attributes on k terms might not be present.
        rule = addSortInjections.addInjections(rule);
        Set<KVariable> existentials = getExistentials(rule);
        ConstructorChecks constructorChecks = new ConstructorChecks(module);
        K left = RewriteToTop.toLeft(rule.body());
        K requires = rule.requires();
        K right =  RewriteToTop.toRight(rule.body());
        K ensures = rule.ensures();
        boolean constructorBased = constructorChecks.isConstructorBased(left);
        RuleInfo ruleInfo = RuleInfo.getRuleInfo(rule, heatCoolEq, topCellSortStr, module, this::getSortStr);
        sb.append("// ");
        sb.append(rule.toString());
        sb.append("\n");
        if (ruleInfo.isCeil && options.disableCeilSimplificationRules) {
          return;
        }
        Set<KVariable> freeVariables = collectLHSFreeVariables(requires, left);
        Map<String,KVariable> freeVarsMap = freeVariables
                .stream().collect(Collectors.toMap(KVariable::name, Function.identity()));
        if (ruleInfo.isEquation) {
            assertNoExistentials(rule, existentials);
            if (rule instanceof Claim) {
                sb.append("  claim{R");
                if (kem != null) // TODO: remove once https://github.com/runtimeverification/haskell-backend/issues/3010 is implemented
                    kem.registerCompilerWarning(KException.ExceptionType.FUTURE_ERROR, "Functional claims not yet supported. https://github.com/runtimeverification/haskell-backend/issues/3010", rule);
            } else {
                sb.append("  axiom{R");
            }
            Option<Sort> sortParamsWrapper = rule.att().getOption(Att.SORT_PARAMS(), Sort.class);
            Option<Set<String>> sortParams = sortParamsWrapper.map(s -> stream(s.params()).map(sort -> sort.name()).collect(Collectors.toSet()));
            if (sortParams.nonEmpty()) {
                for (Object sortParamName : sortParams.get())
                    sb.append("," + sortParamName);
            }
            sb.append("} ");
            if (ruleInfo.isOwise) {
                Set<String> varNames = freeVariables
                        .stream().map(KVariable::name).collect(Collectors.toSet());
                sb.append("\\implies{R} (\n    \\and{R} (\n      \\not{R} (\n        ");
                for (Rule notMatching : RefreshRules.refresh(functionRules.get(ruleInfo.productionLabel), varNames)) {
                    if (ignoreSideConditionsForOwise(notMatching)) {
                        continue;
                    }
                    sb.append("\\or{R} (\n");
                    K notMatchingRequires = notMatching.requires();
                    K notMatchingLeft = RewriteToTop.toLeft(notMatching.body());
                    Set<KVariable> vars = collectLHSFreeVariables(notMatchingRequires, notMatchingLeft);
                    sb.append("          ");
                    for (KVariable var : vars) {
                        sb.append("\\exists{R} (");
                        convert((K)var, sb);
                        sb.append(",\n          ");
                    }
                    sb.append("  \\and{R} (");
                    sb.append("\n              ");
                    convertSideCondition(notMatchingRequires, sb);
                    sb.append(",\n              ");

                    assert notMatchingLeft instanceof KApply : "expecting KApply but got " + notMatchingLeft.getClass();
                    List<K> notMatchingChildren = ((KApply) notMatchingLeft).items();
                    assert notMatchingChildren.size() == ruleInfo.leftChildren.size() : "assuming function with fixed arity";
                    for (int childIdx = 0; childIdx < ruleInfo.leftChildren.size(); childIdx++) {
                        sb.append("\\and{R} (");
                        sb.append("\n                ");
                        sb.append("\\in{");
                        Sort childSort = ruleInfo.prodChildrenSorts.get(childIdx);
                        convert(childSort, ruleInfo.production.params(), sb);
                        sb.append(", R} (");
                        sb.append("\n                  ");
                        sb.append("X").append(childIdx).append(":");
                        convert(childSort, ruleInfo.production.params(), sb);
                        sb.append(",\n                  ");
                        convert(notMatchingChildren.get(childIdx), sb);
                        sb.append("\n                ),");
                    }
                    sb.append("\n                \\top{R} ()");
                    sb.append("\n              ");
                    for (int childIdx = 0; childIdx < ruleInfo.leftChildren.size(); childIdx++) {
                        sb.append(')');
                    }
                    sb.append("\n          )");
                    for (KVariable ignored : vars) {
                        sb.append(")");
                    }
                    sb.append(",\n          ");
                }
                sb.append("\\bottom{R}()");
                sb.append("\n        ");
                for (Rule notMatching : functionRules.get(ruleInfo.productionLabel)) {
                    if (ignoreSideConditionsForOwise(notMatching)) {
                        continue;
                    }
                    sb.append(")");
                }
                sb.append("\n      ),\n      \\and{R}(\n        ");
                convertSideCondition(requires, sb);
                sb.append(",\n        ");

                for (int childIdx = 0; childIdx < ruleInfo.leftChildren.size(); childIdx++) {
                    sb.append("\\and{R} (");
                    sb.append("\n          ");
                    sb.append("\\in{");
                    Sort childSort = ruleInfo.prodChildrenSorts.get(childIdx);
                    convert(childSort, ruleInfo.production.params(), sb);
                    sb.append(", R} (");
                    sb.append("\n            ");
                    sb.append("X").append(childIdx).append(":");
                    convert(childSort, ruleInfo.production.params(), sb);
                    sb.append(",\n            ");
                    convert(ruleInfo.leftChildren.get(childIdx), sb);
                    sb.append("\n          ),");
                }
                sb.append("\n          \\top{R} ()");
                sb.append("\n        ");
                for (int childIdx = 0; childIdx < ruleInfo.leftChildren.size(); childIdx++) {
                    sb.append(')');
                }

                sb.append("\n    )),\n    \\equals{");
                sb.append(ruleInfo.productionSortStr);
                sb.append(",R} (\n      ");
                convert(ruleInfo.productionLabel, sb);
                sb.append("(");
                String conn = "";
                for (int childIdx = 0; childIdx < ruleInfo.leftChildren.size(); childIdx++) {
                    sb.append(conn).append("X").append(childIdx).append(":");
                    Sort childSort = ruleInfo.prodChildrenSorts.get(childIdx);
                    convert(childSort, ruleInfo.production.params(), sb);
                    conn = ",";
                }
                sb.append(")");
                sb.append(",\n     \\and{");
                sb.append(ruleInfo.productionSortStr);
                sb.append("} (\n       ");
                convert(right, sb);
                sb.append(",\n        ");
                convertSideCondition(ensures, ruleInfo.productionSortStr, sb);
                sb.append(")))\n  ");
                convert(rule.att(), sb, freeVarsMap, rule);
                sb.append("\n\n");
            } else if (rule.att().contains(Att.SIMPLIFICATION()) || rule instanceof Claim) {
                sb.append("\\implies{R} (\n    ");
                convertSideCondition(requires, sb);
                sb.append(",\n    \\equals{");
                sb.append(ruleInfo.productionSortStr);
                sb.append(",R} (\n      ");
                convert(left, sb);
                sb.append(",\n     \\and{");
                sb.append(ruleInfo.productionSortStr);
                sb.append("} (\n       ");
                convert(right, sb);
                sb.append(",\n        ");
                convertSideCondition(ensures, ruleInfo.productionSortStr, sb);
                sb.append(")))\n  ");
                convert(rule.att(), sb, freeVarsMap, rule);
                sb.append("\n\n");

            } else {
                sb.append("\\implies{R} (\n    \\and{R}(\n      ");
                convertSideCondition(requires, sb);
                sb.append(",\n      ");

                for (int childIdx = 0; childIdx < ruleInfo.leftChildren.size(); childIdx++) {
                    sb.append("\\and{R} (");
                    sb.append("\n          ");
                    sb.append("\\in{");
                    Sort childSort = ruleInfo.prodChildrenSorts.get(childIdx);
                    convert(childSort, ruleInfo.production.params(), sb);
                    sb.append(", R} (");
                    sb.append("\n            ");
                    sb.append("X").append(childIdx).append(":");
                    convert(childSort, ruleInfo.production.params(), sb);
                    sb.append(",\n            ");
                    convert(ruleInfo.leftChildren.get(childIdx), sb);
                    sb.append("\n          ),");
                }
                sb.append("\n          \\top{R} ()");
                sb.append("\n        ");
                for (int childIdx = 0; childIdx < ruleInfo.leftChildren.size(); childIdx++) {
                    sb.append(')');
                }
                sb.append("),\n    \\equals{");
                sb.append(ruleInfo.productionSortStr);
                sb.append(",R} (\n      ");
                convert(ruleInfo.productionLabel, sb);
                sb.append("(");
                String conn = "";
                for (int childIdx = 0; childIdx < ruleInfo.leftChildren.size(); childIdx++) {
                    sb.append(conn).append("X").append(childIdx).append(":");
                    Sort childSort = ruleInfo.prodChildrenSorts.get(childIdx);
                    convert(childSort, ruleInfo.production.params(), sb);
                    conn = ",";
                }
                sb.append(")");
                sb.append(",\n     \\and{");
                sb.append(ruleInfo.productionSortStr);
                sb.append("} (\n       ");
                convert(right, sb);
                sb.append(",\n        ");
                convertSideCondition(ensures, ruleInfo.productionSortStr, sb);
                sb.append(")))\n  ");
                convert(rule.att(), sb, freeVarsMap, rule);
                sb.append("\n\n");
            }
        } else if (ruleInfo.isKore) {
            assertNoExistentials(rule, existentials);
            if (rule instanceof Claim) {
                sb.append("  claim{} ");
            } else {
                sb.append("  axiom{} ");
            }
            convert(left, sb);
            sb.append("\n  ");
            convert(rule.att(), sb, freeVarsMap, rule);
            sb.append("\n\n");
        } else if (!ExpandMacros.isMacro(rule)) {
            // generate rule LHS
            if (!(rule instanceof Claim)) {
                // LHS for semantics rules
                String ruleAliasName = String.format("rule%dLHS", ruleIndex);
                int priority = getPriority(rule.att());
                List<KVariable> freeVars = new ArrayList<>(freeVariables);
                Comparator<KVariable> compareByName = (KVariable v1, KVariable v2) -> v1.name().compareTo(v2.name());
                java.util.Collections.sort(freeVars, compareByName);

                if (options.enableKoreAntileft) {
                    genAliasForSemanticsRuleLHS(requires, left, ruleAliasName, freeVars, topCellSortStr,
                            priority, priorityToAlias, sb);
                    sb.append("\n");
                }

                sb.append("  axiom{} ");
                sb.append(String.format("\\rewrites{%s} (\n    ", topCellSortStr));

                if (options.enableKoreAntileft) {
                    genSemanticsRuleLHSWithAlias(ruleAliasName, freeVars, topCellSortStr,
                            priorityToPreviousGroup.get(priority), sb);
                    sb.append(",\n    ");
                } else {
                    genSemanticsRuleLHSNoAlias(requires, left, freeVars, topCellSortStr, priorityToPreviousGroup.get(priority), sb);
                    sb.append(",\n      ");
                }
            } else {
                // LHS for claims
                sb.append("  claim{} ");
                sb.append(String.format("\\implies{%s} (\n    ", topCellSortStr));
                sb.append(String.format("  \\and{%s} (\n      ", topCellSortStr));
                convertSideCondition(requires, topCellSortStr, sb);
                sb.append(", ");
                convert(left, sb);
                sb.append("), ");
            }

            // generate rule RHS
            if (sentenceType == SentenceType.ALL_PATH) {
                sb.append(String.format("%s{%s} (\n      ", ALL_PATH_OP, topCellSortStr));
            } else if (sentenceType == SentenceType.ONE_PATH) {
                sb.append(String.format("%s{%s} (\n      ", ONE_PATH_OP, topCellSortStr));
            }
            if (!existentials.isEmpty()) {
                for (KVariable exists : existentials) {
                    sb.append(String.format(" \\exists{%s} (", topCellSortStr));
                    convert((K)exists, sb);
                    sb.append(", ");
                }
                sb.append("\n      ");
            }
            sb.append(String.format("\\and{%s} (\n      ", topCellSortStr));

            if (options.enableKoreAntileft) {
                convertSideCondition(ensures, topCellSortStr, sb);
                sb.append(", ");
                convert(right, sb);
            } else {
                convert(right, sb);
                sb.append(", ");
                convertSideCondition(ensures, topCellSortStr, sb);

            }

            sb.append(')');
            for (KVariable ignored : existentials) {
                sb.append(')');
            }
            if (rule instanceof Claim) {
                sb.append(')');
            }
            sb.append(')');
            sb.append("\n  ");
            convert(rule.att(), sb, freeVarsMap, rule);
            sb.append("\n\n");
        } else {
            assertNoExistentials(rule, existentials);
            sb.append("  axiom{R");
            Option<Sort> sortParamsWrapper = rule.att().getOption(Att.SORT_PARAMS(), Sort.class);
            Option<Set<String>> sortParams = sortParamsWrapper.map(s -> stream(s.params()).map(sort -> sort.name()).collect(Collectors.toSet()));
            if (sortParams.nonEmpty()) {
                for (Object sortParamName : sortParams.get())
                    sb.append("," + sortParamName);
            }
            sb.append("} ");
            sb.append("\\equals{");
            sb.append(ruleInfo.productionSortStr);
            sb.append(",R} (\n    ");
            convert(left, sb);
            sb.append(",\n    ");
            convert(right, sb);
            sb.append(")\n  ");
            convert(rule.att().add(Att.PRIORITY(), Integer.toString(getPriority(rule.att()))), sb, freeVarsMap, rule);
            sb.append("\n\n");
        }
    }

    private boolean ignoreSideConditionsForOwise(Rule notMatching) {
        return notMatching.att().contains(Att.OWISE())
                || notMatching.att().contains(Att.SIMPLIFICATION())
                || notMatching.att().contains(Att.NON_EXECUTABLE());
    }

    private void assertNoExistentials(Sentence sentence, Set<KVariable> existentials) {
        if (!existentials.isEmpty()) {
            throw KEMException.compilerError("Cannot encode equations with existential variables to KORE." +
                    "\n If this is desired, please use #Exists with regular variables." +
                    "\n Offending variables: " + existentials +
                    "\n context: " + sentence);
        }
    }

    private Set<KVariable> getExistentials(RuleOrClaim rule) {
        Set<KVariable> res = new HashSet<>();
        VisitK visitor = new VisitK() {
            @Override
            public void apply(KVariable k) {
                if (k.name().startsWith("?") || k.att().contains(Att.FRESH()))
                    res.add(k);
            }
        };
        visitor.apply(rule.ensures());
        visitor.apply(RewriteToTop.toRight(rule.body()));
        return res;
    }

    private void genAliasForSemanticsRuleLHS(K requires, K left,
                                             String ruleAliasName, List<KVariable> freeVars, String topCellSortStr,
                                             Integer priority, ListMultimap<Integer, String> priorityToAlias,
                                             StringBuilder sb) {
        sb.append("  alias ");
        sb.append(ruleAliasName);
        // We assume no sort variables.
        sb.append("{}(");
        String conn = "";
        for (KVariable var: freeVars) {
            sb.append(conn);
            convert(var.att().getOptional(Sort.class).orElse(Sorts.K()), sb);
            conn = ",";
        }
        sb.append(") : ");
        sb.append(topCellSortStr);
        sb.append("\n  where ");
        genAliasDeclHead(ruleAliasName, freeVars, sb);
        sb.append(") :=\n");
        sb.append(String.format("    \\and{%s} (\n      ", topCellSortStr));
        convertSideCondition(requires, topCellSortStr, sb);
        sb.append(", ");
        convert(left, sb);
        sb.append(") []\n");

        // build existential quantified pattern for alias
        StringBuilder extStrBuilder = new StringBuilder();
        for (KVariable var: freeVars) {
            extStrBuilder.append(String.format("\\exists{%s}(", topCellSortStr));
            convert((K)var, extStrBuilder);
            extStrBuilder.append(",");
        }
        genAliasDeclHead(ruleAliasName, freeVars, extStrBuilder);
        extStrBuilder.append(")");
        for (int i = 0; i < freeVars.size(); i++) {
            extStrBuilder.append(")");
        }
        priorityToAlias.put(priority, extStrBuilder.toString());
    }

    private void genAliasDeclHead(String aliasName, List<KVariable> freeVars, StringBuilder sb) {
        sb.append(aliasName);
        sb.append("{}(");
        String conn = "";
        for (KVariable var: freeVars) {
            sb.append(conn);
            convert((K)var, sb);
            conn = ",";
        }
    }

    private void genSemanticsRuleLHSWithAlias(String ruleAliasName, List<KVariable> freeVars, String topCellSortStr,
                                              String previousGroupName, StringBuilder sb) {
        if (!previousGroupName.equals("")) {
            sb.append(String.format("\\and{%s}(\n      ", topCellSortStr));
            sb.append(String.format("\\not{%s}(", topCellSortStr));
            sb.append(previousGroupName);
            sb.append("{}()),\n      ");
        }
        sb.append(ruleAliasName);
        sb.append("{}(");
        String conn = "";
        for (KVariable var: freeVars) {
            sb.append(conn);
            convert((K)var, sb);
            conn = ",";
        }
        sb.append(")");
        if (!previousGroupName.equals("")) {
            sb.append(")");
        }
    }

    private void genSemanticsRuleLHSNoAlias(K requires, K left, List<KVariable> freeVars, String topCellSortStr,
                                            String previousGroupName, StringBuilder sb) {
        sb.append(String.format("  \\and{%s} (\n        ", topCellSortStr));
        convert(left, sb);
        sb.append(",\n        ");
        convertSideCondition(requires, topCellSortStr, sb);
        sb.append(")");
    }

    private void genPriorityGroups(List<Integer> priorityList,
                                   Map<Integer, String> priorityToPreviousGroup,
                                   ListMultimap<Integer, String> priorityToAlias,
                                   String topCellSortStr, StringBuilder sb) {
        // skip generating alias for the last priority group
        for (int index = 0; index < priorityList.size()-1; index++) {
            Integer priority = priorityList.get(index);
            String priorityGroupName = String.format("priorityLE%d", priority);
            sb.append(String.format("  alias %s{}() : %s", priorityGroupName, topCellSortStr));
            sb.append(String.format("\n  where %s{}() := ", priorityGroupName));
            String previousGroupName = priorityToPreviousGroup.get(priority);
            if (!previousGroupName.equals("")) {
                sb.append(String.format("\\or{%s}(\n    ", topCellSortStr));
                sb.append(previousGroupName);
                sb.append("{}(), ");
            }
            // generate priority group body
            List<String> aliases = priorityToAlias.get(priority);
            for (String ruleLHSAlias : aliases) {
                sb.append(String.format("\\or{%s}(\n    ", topCellSortStr));
                sb.append(ruleLHSAlias);
                sb.append(", ");
            }
            // bottom is the unit of "or"
            sb.append(String.format("\\bottom{%s}()", topCellSortStr));
            // complete parenthesis
            for (int i = 0; i < aliases.size(); i++) {
                sb.append(")");
            }
            if (!previousGroupName.equals("")) {
                sb.append(")");
            }
            sb.append(" []\n\n");
        }
    }

    private void functionalPattern(Production prod, Runnable functionPattern, StringBuilder sb) {
        sb.append("  axiom");
        convertParams(prod.klabel(), true, sb);
        sb.append(" \\exists{R} (Val:");
        convert(prod.sort(), prod, sb);
        sb.append(", \\equals{");
        convert(prod.sort(), prod, sb);
        sb.append(", R} (");
        sb.append("Val:");
        convert(prod.sort(), prod, sb);
        sb.append(", ");
        functionPattern.run();
        sb.append("))");
    }

    private void applyPattern(Production prod, String varName, StringBuilder sb) {
        convert(prod.klabel().get(), prod, sb);
        sb.append("(");
        String conn = "";
        for (int i = 0; i < prod.arity(); i++) {
            sb.append(conn);
            sb.append(varName).append(i).append(":");
            convert(prod.nonterminal(i).sort(), prod, sb);
            conn = ", ";
        }
        sb.append(')');
    }

    private void convertTokenProd(Sort sort, StringBuilder sb) {
        if (METAVAR) {
            sb.append("\\exists{");
            convert(sort, sb);
            sb.append("} (#Str:#String{}, \\dv{");
            convert(sort, sb);
            sb.append("}(#Str:#String{}))");
        } else {
            sb.append("\\top{");
            convert(sort, sb);
            sb.append("}()");
        }
    }

    private void convertParams(Option<KLabel> maybeKLabel, boolean hasR, StringBuilder sb) {
        sb.append("{");
        String conn = "";
        if (hasR) {
            sb.append("R");
            if (maybeKLabel.isDefined()) {
                conn = ", ";
            }
        }
        if (maybeKLabel.isDefined()) {
            for (Sort param : iterable(maybeKLabel.get().params())) {
                sb.append(conn);
                convert(param, Seq(param), sb);
                conn = ", ";
            }
        }
        sb.append("}");
    }

    private boolean isFunction(Production prod) {
        return prod.att().contains(Att.FUNCTION());
    }

    // Assume that there is no quantifiers
    private Set<KVariable> collectLHSFreeVariables(K requires, K left) {
        Set<KVariable> res = new HashSet<>();
        VisitK visitor = new VisitK() {
            @Override
            public void apply(KVariable k) {
                res.add(k);
            }
        };
        visitor.apply(requires);
        visitor.apply(left);
        return res;
    }

    private void convertSideCondition(K k, StringBuilder sb) {
        if (k.equals(BooleanUtils.TRUE)) {
            sb.append("\\top{R}()");
        } else {
            sb.append("\\equals{SortBool{},R}(\n        ");
            convert(k, sb);
            sb.append(",\n        \\dv{SortBool{}}(\"true\"))");
        }
    }

    private void convertSideCondition(K k, String resultSortStr, StringBuilder sb) {
        if (k.equals(BooleanUtils.TRUE)) {
            sb.append(String.format("\\top{%s}()", resultSortStr));
        } else {
            sb.append(String.format("\\equals{SortBool{},%s}(\n        ", resultSortStr));
            convert(k, sb);
            sb.append(",\n        \\dv{SortBool{}}(\"true\"))");
        }
    }

    private KLabel computePolyKLabel(KApply k) {
        String labelName = k.klabel().name();
        if (mlBinders.contains(labelName)) { // ML binders are not parametric in the variable so we remove it
            List<Sort> params = mutable(k.klabel().params());
            if (!params.isEmpty()) {
              params.remove(0);
            }
            return KLabel(labelName, immutable(params));
        } else {
            return k.klabel();
        }
    }

    private static String convertBuiltinLabel(String klabel) {
      switch(klabel) {
      case "#Bottom":
        return "\\bottom";
      case "#Top":
        return "\\top";
      case "#Or":
        return "\\or";
      case "#And":
        return "\\and";
      case "#Not":
        return "\\not";
      case "#Floor":
        return "\\floor";
      case "#Ceil":
        return "\\ceil";
      case "#Equals":
        return "\\equals";
      case "#Implies":
        return "\\implies";
      case "#Exists":
        return "\\exists";
      case "#Forall":
        return "\\forall";
      case "#AG":
        return "allPathGlobally";
      case "weakExistsFinally":
        return ONE_PATH_OP;
      case "weakAlwaysFinally":
        return ALL_PATH_OP;
      default:
        throw KEMException.compilerError("Unsuppored kore connective in rule: " + klabel);
      }
    }

    public static void convert(KLabel klabel, StringBuilder sb) {
        convert(klabel, java.util.Collections.emptySet(), sb);
    }

    private void convert(KLabel klabel, Seq<Sort> params, StringBuilder sb) {
        convert(klabel, mutable(params), sb);
    }

    private static void convert(KLabel klabel, Collection<Sort> params, StringBuilder sb) {
        if (klabel.name().equals(KLabels.INJ)) {
            sb.append(klabel.name());
        } else if (ConstructorChecks.isBuiltinLabel(klabel)) {
            sb.append(convertBuiltinLabel(klabel.name()));
        } else {
            sb.append("Lbl");
            convert(klabel.name(), sb);
        }
        sb.append("{");
        String conn = "";
        for (Sort param : iterable(klabel.params())) {
            sb.append(conn);
            convert(param, params, sb);
            conn = ", ";
        }
        sb.append("}");
    }

    private void convert(KLabel klabel, Production prod, StringBuilder sb) {
        if (klabel.name().equals(KLabels.INJ)) {
            sb.append(klabel.name());
        } else {
            sb.append("Lbl");
            convert(klabel.name(), sb);
        }
        sb.append("{");
        String conn = "";
        for (Sort param : iterable(klabel.params())) {
            sb.append(conn);
            convert(param, prod, sb);
            conn = ", ";
        }
        sb.append("}");
    }

    private void convert(Sort sort, Production prod, StringBuilder sb) {
        convert(sort, prod.params(), sb);
    }

    public static void convert(Sort sort, StringBuilder sb) {
        convert(sort, java.util.Collections.emptySet(), sb);
    }

    private void convert(SortHead sort, StringBuilder sb) {
      List<Sort> params = new ArrayList<>();
      for (int i = 0; i < sort.params(); i++) {
        params.add(Sort("S" + i));
      }
      convert(Sort(sort.name(), immutable(params)), params, sb);
    }

    private void convert(Sort sort, Seq<Sort> params, StringBuilder sb) {
        convert(sort, mutable(params), sb);
    }

    private static void convert(Sort sort, Collection<Sort> params, StringBuilder sb) {
        if (sort.name().equals(AddSortInjections.SORTPARAM_NAME)) {
            String sortVar = sort.params().headOption().get().name();
            sb.append(sortVar);
            return;
        }
        sb.append("Sort");
        convert(sort.name(), sb);
        if (!params.contains(sort)) {
            sb.append("{");
            String conn = "";
            for (Sort param : iterable(sort.params())) {
                sb.append(conn);
                convert(param, params, sb);
                conn = ", ";
            }
            sb.append("}");
        }
    }

    private String getSortStr(Sort sort) {
        StringBuilder strBuilder = new StringBuilder();
        convert(sort, strBuilder);
        return strBuilder.toString();
    }

    private void convert(Att att, StringBuilder sb, Map<String, KVariable> freeVarsMap, HasLocation location) {
        sb.append("[");
        String conn = "";

        // Emit user groups as group(_) to prevent conflicts between user groups and internals
        att = att.withUserGroupsAsGroupAtt();

        for (Tuple2<Tuple2<Att.Key, String>, ?> attribute :
            // Sort to stabilize error messages
            stream(att.att()).sorted(Comparator.comparing(Tuple2::toString)).collect(Collectors.toList())) {
            Att.Key key = attribute._1._1;
            String strKey = key.key();
            String clsName = attribute._1._2;
            Object val = attribute._2;
            String strVal = val.toString();
            sb.append(conn);
            if (clsName.equals(K.class.getName())) {
                convert(strKey, sb);
                sb.append("{}(");
                convert((K) val, sb);
                sb.append(")");
            } else if (clsName.equals(KList.class.getName())) {
                convert(strKey, sb);
                sb.append("{}(");
                String conn2 = "";
                for (K item : ((KList)val).items()) {
                  sb.append(conn2);
                  convert(item, sb);
                  conn2 = ",";
                }
                sb.append(")");
            } else if (module.hasAtt(key)) {
                convert(strKey, sb);
                sb.append("{}(");
                if (isListOfVarsAttribute(key)) {
                    convertStringVarList(location, freeVarsMap, strVal, sb);
                } else {
                    switch (strKey) {
                        case "unit":
                        case "element":
                            Production prod = module.production(KApply(KLabel(strVal)));
                            convert(prod.klabel().get(), prod.params(), sb);
                            sb.append("()");
                            break;
                        default:
                            sb.append(StringUtil.enquoteKString(strVal));
                    }
                }
                sb.append(")");
            } else {
                convert(strKey, sb);
                sb.append("{}()");
            }
            conn = ", ";
        }
        sb.append("]");
    }

    private void convertStringVarList(HasLocation location, Map<String, KVariable> freeVarsMap, String strVal, StringBuilder sb) {
        if (strVal.trim().isEmpty()) return;
        Collection<KVariable> variables = Arrays.stream(strVal.split(",")).map(String::trim)
                .map(s -> {
                    if (freeVarsMap.containsKey(s)) return freeVarsMap.get(s);
                    else throw KEMException.criticalError("No free variable found for " + s, location);
                }).collect(Collectors.toList());
        String conn = "";
        for (KVariable var : variables) {
            sb.append(conn);
            convert((K) var, sb);
            conn = ",";
        }
    }

    private boolean isListOfVarsAttribute(Att.Key name) {
        return name.equals(Att.CONCRETE()) || name.equals(Att.SYMBOLIC());
    }

    private static String[] asciiReadableEncodingKoreCalc() {
        String[] koreEncoder = Arrays.copyOf(StringUtil.asciiReadableEncodingDefault, StringUtil.asciiReadableEncodingDefault.length);
        koreEncoder[0x26] = "And-";
        koreEncoder[0x3c] = "-LT-";
        koreEncoder[0x3e] = "-GT-";
        koreEncoder[0x40] = "-AT-";
        koreEncoder[0x5e] = "Xor-";
        return koreEncoder;
    }

    private static final Pattern identChar = Pattern.compile("[A-Za-z0-9\\-]");
    public static String[] asciiReadableEncodingKore = asciiReadableEncodingKoreCalc();

    private static void convert(String name, StringBuilder sb) {
        switch(name) {
        case "module":
        case "endmodule":
        case "sort":
        case "hooked-sort":
        case "symbol":
        case "hooked-symbol":
        case "alias":
        case "axiom":
            sb.append(name).append("'Kywd'");
            return;
        default: break;
        }
        StringBuilder buffer = new StringBuilder();
        StringUtil.encodeStringToAlphanumeric(buffer, name, asciiReadableEncodingKore, identChar, "'");
        sb.append(buffer);
    }

    public Set<K> collectAnonymousVariables(K k){
        Set<K> anonymousVariables = new HashSet<>();
        new VisitK() {
            @Override
            public void apply(KApply k) {
                if (mlBinders.contains(k.klabel().name()) && k.items().get(0).att().contains(Att.ANONYMOUS())){
                    throw KEMException.internalError("Nested quantifier over anonymous variables.");
                }
                for (K item : k.items()) {
                    apply(item);
                }
            }

            @Override
            public void apply(KVariable k) {
                if (k.att().contains(Att.ANONYMOUS())) {
                    anonymousVariables.add(k);
                }
            }

        }.apply(k);
        return anonymousVariables;
    }

    public void convert(K k, StringBuilder sb) {
        new VisitK() {
            @Override
            public void apply(KApply k) {
                KLabel label = computePolyKLabel(k);
                String conn = "";
                if (mlBinders.contains(k.klabel().name()) && k.items().get(0).att().contains(Att.ANONYMOUS())){
                    // Handle #Forall _ / #Exists _
                    Set<K> anonymousVariables = collectAnonymousVariables(k.items().get(1));

                    // Quantify over all anonymous variables.
                    for (K variable : anonymousVariables) {
                        sb.append(conn);
                        convert(label, sb);
                        sb.append("(");
                        apply(variable);
                        conn = ",";
                    }

                    // We assume that mlBinder only has two children.
                    sb.append(conn);
                    apply(k.items().get(1));

                    for (int i = 0; i < anonymousVariables.size(); i++) {
                        sb.append(")");
                    }
                } else {
                    convert(label, sb);
                    sb.append("(");
                    for (K item : k.items()) {
                        sb.append(conn);
                        apply(item);
                        conn = ",";
                    }
                    sb.append(")");
                }
            }

            @Override
            public void apply(KToken k) {
                sb.append("\\dv{");
                convert(k.sort(), sb);
                sb.append("}(");
                if (module.sortAttributesFor().get(k.sort().head()).getOrElse(Att::empty).getOptional(Att.HOOK()).orElse("").equals("STRING.String")) {
                    sb.append(StringUtil.escapeNonASCII(k.s()));
                } else if (module.sortAttributesFor().get(k.sort().head()).getOrElse(Att::empty).getOptional(Att.HOOK()).orElse("").equals("BYTES.Bytes")) {
                    sb.append(StringUtil.escapeNonASCII(k.s().substring(1))); // remove the leading `b`
                } else {
                    sb.append(StringUtil.enquoteKString(k.s()));
                }
                sb.append(")");
            }

            @Override
            public void apply(KSequence k) {
                for (int i = 0; i < k.items().size(); i++) {
                    K item = k.items().get(i);
                    boolean isList = item.att().get(Sort.class).equals(Sorts.K());
                    if (i == k.items().size() - 1) {
                        if (isList) {
                            apply(item);
                        } else {
                            sb.append("kseq{}(");
                            apply(item);
                            sb.append(",dotk{}())");
                        }
                    } else {
                        if (item.att().get(Sort.class).equals(Sorts.K())) {
                            sb.append("append{}(");
                        } else {
                            sb.append("kseq{}(");
                        }
                        apply(item);
                        sb.append(",");
                    }
                }
                if (k.items().size() == 0) {
                    sb.append("dotk{}()");
                }
                for (int i = 0; i < k.items().size() - 1; i++) {
                    sb.append(")");
                }
            }

            @Override
            public void apply(KVariable k) {
                boolean setVar = k.name().startsWith("@");
                if (setVar) {
                    sb.append('@');
                }
                sb.append("Var");
                String name = setVar ? k.name().substring(1) : k.name();
                convert(name, sb);
                sb.append(":");
                convert(k.att().getOptional(Sort.class).orElse(Sorts.K()), sb);
            }

            @Override
            public void apply(KRewrite k) {
                sb.append("\\rewrites{");
                convert(k.att().get(Sort.class), sb);
                sb.append("}(");
                apply(k.left());
                sb.append(",");
                apply(k.right());
                sb.append(")");
            }

            @Override
            public void apply(KAs k) {
                Sort sort = k.att().get(Sort.class);
                sb.append("\\and{");
                convert(sort, sb);
                sb.append("}(");
                apply(k.pattern());
                sb.append(",");
                apply(k.alias());
                sb.append(")");
            }

            @Override
            public void apply(InjectedKLabel k) {
                throw KEMException.internalError("Cannot yet translate #klabel to kore", k);
            }
        }.apply(k);
    }
}
