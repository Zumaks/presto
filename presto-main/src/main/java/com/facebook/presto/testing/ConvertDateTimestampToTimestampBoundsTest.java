package com.facebook.presto.testing;

import com.facebook.presto.common.function.OperatorType;
import com.facebook.presto.spi.relation.CallExpression;
import com.facebook.presto.spi.relation.ConstantExpression;
import com.facebook.presto.spi.relation.SpecialFormExpression;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A self-contained JUnit 5 test for ConvertDateTimestampToTimestampBounds.
 * It defines minimal stubs of Presto interfaces so that it is possible to compile & run
 * without the full Presto codebase.
 */
public class ConvertDateTimestampToTimestampBoundsTest
{
    //
    // -------------------------------------------------------------------
    //  1) Minimal Presto-like interfaces/types
    // -------------------------------------------------------------------
    //

    interface RowExpressionVisitor<R, C>
    {
        R visitCall(DummyCallExpression call, C context);
        R visitConstant(DummyConstantExpression literal, C context);
        R visitVariableReference(DummyVariableExpression variable, C context);
    }

    // 1B) RowExpression with a getType() method returning our minimal Type
    interface RowExpression
    {
        <R, C> R accept(RowExpressionVisitor<R, C> visitor, C context);

        // Minimal Type from "com.facebook.presto.spi.type.Type"
        Type getType();
    }

    // 1C) Minimal Type interface (like com.facebook.presto.spi.type.Type)
    interface Type
    {
        String getDisplayName();
    }

    // 1D) Minimal PlanNode
    interface PlanNode
    {
        String getId();
        List<PlanNode> getSources();
        <R, C> R accept(PlanVisitor<R, C> visitor, C context);
    }

    // 1E) Minimal PlanVisitor
    interface PlanVisitor<R, C>
    {
        R visitPlan(PlanNode node, C context);
    }

    // 1F) Minimal FilterNode
    interface FilterNode extends PlanNode
    {
        RowExpression getPredicate();
    }

    // 1G) The “Rule” interface with Pattern, Captures, Context, etc.
    interface Rule<T extends PlanNode>
    {
        Pattern<T> getPattern();

        Result apply(T node, Captures captures, Context context);

        // Minimal sub-interfaces/structs
        interface Context {}
        interface Result {}
    }

    interface Captures {}

    interface Pattern<T> {}

    // 1H) A utility that returns a "filter" pattern
    static <T extends FilterNode> Pattern<T> filter()
    {
        return new Pattern<T>() {};
    }

    //
    // -------------------------------------------------------------------
    //  2) Minimal "FunctionAndTypeManager" stubs
    // -------------------------------------------------------------------
    //

    /**
     * Simulates com.facebook.presto.metadata.FunctionAndTypeManager.
     * Real code might have many methods, but for now only defines what's needed
     * for the Rule constructor.
     */
    interface FunctionAndTypeManager
    {
        FunctionAndTypeResolver getFunctionAndTypeResolver();
    }

    interface FunctionAndTypeResolver
    {
        // put anything you need here, or leave it empty
    }

    /**
     * A dummy implementation. The rule just needs an instance to call
     * getFunctionAndTypeResolver().
     */
    static class DummyFunctionAndTypeManager implements FunctionAndTypeManager
    {
        @Override
        public FunctionAndTypeResolver getFunctionAndTypeResolver()
        {
            return new DummyFunctionAndTypeResolver();
        }
    }

    static class DummyFunctionAndTypeResolver implements FunctionAndTypeResolver
    {
    }

    //
    // -------------------------------------------------------------------
    //  3) Minimal *Result* implementation so the Rule can return something
    // -------------------------------------------------------------------
    //

    static class MyResult implements Rule.Result
    {
        private final Optional<PlanNode> transformed;

        private MyResult(Optional<PlanNode> transformed)
        {
            this.transformed = transformed;
        }

        static MyResult empty()
        {
            return new MyResult(Optional.empty());
        }

        static MyResult ofPlanNode(PlanNode node)
        {
            return new MyResult(Optional.of(node));
        }

        public boolean isEmpty()
        {
            return !transformed.isPresent();
        }

        public PlanNode getPlanNode()
        {
            return transformed.get();
        }
    }

    //
    // -------------------------------------------------------------------
    //  4) Dummy plan node & filter node implementations
    // -------------------------------------------------------------------
    //

    static class DummyPlanNode implements PlanNode
    {
        private final String id;

        DummyPlanNode(String id)
        {
            this.id = id;
        }

        @Override
        public String getId()
        {
            return id;
        }

        @Override
        public List<PlanNode> getSources()
        {
            return Collections.emptyList();
        }

        @Override
        public <R, C> R accept(PlanVisitor<R, C> visitor, C context)
        {
            return visitor.visitPlan(this, context);
        }

        @Override
        public String toString()
        {
            return "DummyPlanNode(" + id + ")";
        }
    }

    static class DummyFilterNode implements FilterNode
    {
        private final String id;
        private final PlanNode source;
        private final RowExpression predicate;

        DummyFilterNode(String id, PlanNode source, RowExpression predicate)
        {
            this.id = id;
            this.source = source;
            this.predicate = predicate;
        }

        @Override
        public RowExpression getPredicate()
        {
            return predicate;
        }

        @Override
        public String getId()
        {
            return id;
        }

        @Override
        public List<PlanNode> getSources()
        {
            return Collections.singletonList(source);
        }

        @Override
        public <R, C> R accept(PlanVisitor<R, C> visitor, C context)
        {
            return visitor.visitPlan(this, context);
        }

        @Override
        public String toString()
        {
            return "DummyFilterNode(" + id + ")";
        }
    }

    //
    // -------------------------------------------------------------------
    //  5) Dummy RowExpressions (Call, Constant, Variable)
    // -------------------------------------------------------------------
    //

    // A minimal Type implementation for testing
    static class DummyType implements Type
    {
        private final String name;

        DummyType(String name)
        {
            this.name = name;
        }

        @Override
        public String getDisplayName()
        {
            return name;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }

    // 5A) Dummy CallExpression
    static class DummyCallExpression implements RowExpression
    {
        private final String displayName; // e.g. "=", "date", "and", "year", "month"
        private final DummyType type;     // e.g. "boolean", "date", "timestamp", "integer"
        private final List<RowExpression> arguments;

        DummyCallExpression(String displayName, String typeName, List<RowExpression> arguments)
        {
            this.displayName = displayName;
            this.type = new DummyType(typeName);
            this.arguments = arguments;
        }

        public String getDisplayName()
        {
            return displayName;
        }

        public List<RowExpression> getArguments()
        {
            return arguments;
        }

        @Override
        public Type getType()
        {
            return type;
        }

        @Override
        public <R, C> R accept(RowExpressionVisitor<R, C> visitor, C context)
        {
            return visitor.visitCall(this, context);
        }

        @Override
        public boolean equals(Object o)
        {
            return this == o;
        }

        @Override
        public int hashCode()
        {
            return System.identityHashCode(this);
        }

        @Override
        public String toString()
        {
            return displayName + arguments;
        }
    }

    // 5B) Dummy ConstantExpression
    static class DummyConstantExpression implements RowExpression
    {
        private final Object value;
        private final DummyType type;

        DummyConstantExpression(Object value, String typeName)
        {
            this.value = value;
            this.type = new DummyType(typeName);
        }

        public Object getValue()
        {
            return value;
        }

        @Override
        public Type getType()
        {
            return type;
        }

        @Override
        public <R, C> R accept(RowExpressionVisitor<R, C> visitor, C context)
        {
            return visitor.visitConstant(this, context);
        }

        @Override
        public boolean equals(Object o)
        {
            return this == o;
        }

        @Override
        public int hashCode()
        {
            return System.identityHashCode(this);
        }

        @Override
        public String toString()
        {
            return String.valueOf(value);
        }
    }

    // 5C) Dummy Variable (column) expression
    static class DummyVariableExpression implements RowExpression
    {
        private final String name;
        private final DummyType type;

        DummyVariableExpression(String name, String typeName)
        {
            this.name = name;
            this.type = new DummyType(typeName);
        }

        @Override
        public Type getType()
        {
            return type;
        }

        @Override
        public <R, C> R accept(RowExpressionVisitor<R, C> visitor, C context)
        {
            return visitor.visitVariableReference(this, context);
        }

        @Override
        public boolean equals(Object o)
        {
            return this == o;
        }

        @Override
        public int hashCode()
        {
            return System.identityHashCode(this);
        }

        @Override
        public String toString()
        {
            return name;
        }
    }

    //
    // -------------------------------------------------------------------
    //  6) The actual ConvertDateTimestampToTimestampBounds rule
    // -------------------------------------------------------------------
    //

    /**
     * Minimal version of the rule that checks for
     *    date(ts_col) = DATE 'yyyy-mm-dd'
     * and rewrites to a pair of comparisons on timestamps.
     *
     * Extended to also support:
     *    year(ts_col) = <numeric literal>
     * and
     *    month(ts_col) = 'YYYY-MM'
     * which will be rewritten to timestamp comparisons for the given time period.
     */
    static class ConvertDateTimestampToTimestampBounds implements Rule<FilterNode>
    {
        // We rely on the "filter()" pattern above
        private static final Pattern<FilterNode> PATTERN = filter();

        private final FunctionAndTypeManager functionAndTypeManager;

        public ConvertDateTimestampToTimestampBounds(FunctionAndTypeManager functionAndTypeManager)
        {
            this.functionAndTypeManager = functionAndTypeManager;
        }

        @Override
        public Pattern<FilterNode> getPattern()
        {
            return PATTERN;
        }

        @Override
        public Rule.Result apply(FilterNode filter, Captures captures, Rule.Context context)
        {
            RowExpression originalPredicate = filter.getPredicate();
            RowExpression rewritten = rewritePredicate(originalPredicate);

            if (rewritten.equals(originalPredicate)) {
                return MyResult.empty();
            }

            FilterNode newFilter = new DummyFilterNode(filter.getId(), filter.getSources().get(0), rewritten);
            return MyResult.ofPlanNode(newFilter);
        }

        private RowExpression rewritePredicate(RowExpression expression)
        {
            // Only handling binary comparison expressions
            if (!(expression instanceof DummyCallExpression)) {
                return expression;
            }

            DummyCallExpression call = (DummyCallExpression) expression;

            // Proceed only for equality comparisons of two arguments
            if (!call.getDisplayName().equals(EQUAL.getFunctionName()) || call.getArguments().size() != 2) {
                return expression;
            }

            RowExpression left = call.getArguments().get(0);
            RowExpression right = call.getArguments().get(1);

            // --- Existing logic for date() and year() functions ---

            Optional<RowExpression> maybeDateCol = extractDateFunctionArgument(left, right);
            Optional<DummyConstantExpression> maybeDateLiteral = extractDateLiteral(left, right);

            if (maybeDateCol.isPresent() && maybeDateLiteral.isPresent()) {
                String dateValue = maybeDateLiteral.get().getValue().toString();
                RowExpression lowerBound = new DummyCallExpression(
                        ">=", "boolean",
                        Arrays.asList(
                                maybeDateCol.get(),
                                new DummyConstantExpression(dateValue + " 00:00:00.000", "timestamp")));

                RowExpression upperBound = new DummyCallExpression(
                        "<", "boolean",
                        Arrays.asList(
                                maybeDateCol.get(),
                                new DummyConstantExpression(dateValue + "+1 00:00:00.000", "timestamp")));

                return new DummyCallExpression("and", "boolean", Arrays.asList(lowerBound, upperBound));
            }

            Optional<RowExpression> maybeYearCol = extractYearFunctionArgument(left, right);
            Optional<DummyConstantExpression> maybeYearLiteral = extractYearLiteral(left, right);

            if (maybeYearCol.isPresent() && maybeYearLiteral.isPresent()) {
                Object literalVal = maybeYearLiteral.get().getValue();
                long year;
                if (literalVal instanceof Number) {
                    year = ((Number) literalVal).longValue();
                } else {
                    return expression;
                }

                String lowerTimestamp = String.format("%d-01-01 00:00:00.000", year);
                String upperTimestamp = String.format("%d-01-01 00:00:00.000", year + 1);

                RowExpression lowerBound = new DummyCallExpression(
                        ">=", "boolean",
                        Arrays.asList(
                                maybeYearCol.get(),
                                new DummyConstantExpression(lowerTimestamp, "timestamp")));

                RowExpression upperBound = new DummyCallExpression(
                        "<", "boolean",
                        Arrays.asList(
                                maybeYearCol.get(),
                                new DummyConstantExpression(upperTimestamp, "timestamp")));

                return new DummyCallExpression("and", "boolean", Arrays.asList(lowerBound, upperBound));
            }

            // --- New logic for month() function ---
            Optional<RowExpression> maybeMonthCol = extractMonthFunctionArgument(left, right);
            Optional<DummyConstantExpression> maybeMonthLiteral = extractMonthLiteral(left, right);

            if (maybeMonthCol.isPresent() && maybeMonthLiteral.isPresent()) {
                // Assume literal is a string in "YYYY-MM" format.
                String monthValue = maybeMonthLiteral.get().getValue().toString();
                String[] parts = monthValue.split("-");
                if (parts.length != 2) {
                    return expression;
                }
                int yearPart, monthPart;
                try {
                    yearPart = Integer.parseInt(parts[0]);
                    monthPart = Integer.parseInt(parts[1]);
                }
                catch (NumberFormatException e) {
                    return expression;
                }
                if (monthPart < 1 || monthPart > 12) {
                    return expression;
                }
                // Lower bound: first day of the month.
                String lowerTimestamp = String.format("%d-%02d-01 00:00:00.000", yearPart, monthPart);
                // Upper bound: first day of the next month. Handle December specially.
                String upperTimestamp;
                if (monthPart == 12) {
                    upperTimestamp = String.format("%d-01-01 00:00:00.000", yearPart + 1);
                }
                else {
                    upperTimestamp = String.format("%d-%02d-01 00:00:00.000", yearPart, monthPart + 1);
                }

                RowExpression lowerBound = new DummyCallExpression(
                        ">=", "boolean",
                        Arrays.asList(
                                maybeMonthCol.get(),
                                new DummyConstantExpression(lowerTimestamp, "timestamp")));

                RowExpression upperBound = new DummyCallExpression(
                        "<", "boolean",
                        Arrays.asList(
                                maybeMonthCol.get(),
                                new DummyConstantExpression(upperTimestamp, "timestamp")));

                return new DummyCallExpression("and", "boolean", Arrays.asList(lowerBound, upperBound));
            }

            // If none matched, return the expression unchanged.
            return expression;
        }

        private DummyCallExpression createAndExpression(RowExpression left, RowExpression right)
        {
            // For simplicity in this dummy implementation, we reuse the constructor.
            return new DummyCallExpression("and", "boolean", Arrays.asList(left, right));
        }

        // --- Date Function Extraction (for date()) ---

        private Optional<RowExpression> extractDateFunctionArgument(RowExpression first, RowExpression second)
        {
            Optional<RowExpression> firstCheck = extractDateFunction(first);
            if (firstCheck.isPresent()) {
                return firstCheck;
            }
            return extractDateFunction(second);
        }

        private Optional<DummyConstantExpression> extractDateLiteral(RowExpression first, RowExpression second)
        {
            Optional<DummyConstantExpression> firstCheck = extractConstantDateLiteral(first);
            if (firstCheck.isPresent()) {
                return firstCheck;
            }
            return extractConstantDateLiteral(second);
        }

        private Optional<RowExpression> extractDateFunction(RowExpression expr)
        {
            if (!(expr instanceof DummyCallExpression)) {
                return Optional.empty();
            }
            DummyCallExpression call = (DummyCallExpression) expr;
            boolean isDateFunction = call.getDisplayName().equalsIgnoreCase("date") &&
                    call.getType().toString().equalsIgnoreCase("date");
            if (!isDateFunction || call.getArguments().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(call.getArguments().get(0));
        }

        private Optional<DummyConstantExpression> extractConstantDateLiteral(RowExpression expr)
        {
            if (!(expr instanceof DummyConstantExpression)) {
                return Optional.empty();
            }
            return Optional.of((DummyConstantExpression) expr);
        }

        // --- Year Function Extraction (for year()) ---
        private Optional<RowExpression> extractYearFunctionArgument(RowExpression first, RowExpression second)
        {
            Optional<RowExpression> firstCheck = extractYearFunction(first);
            if (firstCheck.isPresent()) {
                return firstCheck;
            }
            return extractYearFunction(second);
        }

        private Optional<DummyConstantExpression> extractYearLiteral(RowExpression first, RowExpression second)
        {
            Optional<DummyConstantExpression> firstCheck = extractConstantYearLiteral(first);
            if (firstCheck.isPresent()) {
                return firstCheck;
            }
            return extractConstantYearLiteral(second);
        }

        private Optional<RowExpression> extractYearFunction(RowExpression expr)
        {
            if (!(expr instanceof DummyCallExpression)) {
                return Optional.empty();
            }
            DummyCallExpression call = (DummyCallExpression) expr;
            if (!call.getDisplayName().equalsIgnoreCase("year") || call.getArguments().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(call.getArguments().get(0));
        }

        private Optional<DummyConstantExpression> extractConstantYearLiteral(RowExpression expr)
        {
            if (!(expr instanceof DummyConstantExpression)) {
                return Optional.empty();
            }
            DummyConstantExpression c = (DummyConstantExpression) expr;
            Object value = c.getValue();
            if (value instanceof Number) {
                return Optional.of(c);
            }
            return Optional.empty();
        }

        // --- Month Function Extraction (for month()) ---
        private Optional<RowExpression> extractMonthFunctionArgument(RowExpression first, RowExpression second)
        {
            Optional<RowExpression> firstCheck = extractMonthFunction(first);
            if (firstCheck.isPresent()) {
                return firstCheck;
            }
            return extractMonthFunction(second);
        }

        private Optional<DummyConstantExpression> extractMonthLiteral(RowExpression first, RowExpression second)
        {
            Optional<DummyConstantExpression> firstCheck = extractConstantMonthLiteral(first);
            if (firstCheck.isPresent()) {
                return firstCheck;
            }
            return extractConstantMonthLiteral(second);
        }

        private Optional<RowExpression> extractMonthFunction(RowExpression expr)
        {
            if (!(expr instanceof DummyCallExpression)) {
                return Optional.empty();
            }
            DummyCallExpression call = (DummyCallExpression) expr;
            if (!call.getDisplayName().equalsIgnoreCase("month") || call.getArguments().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(call.getArguments().get(0));
        }

        private Optional<DummyConstantExpression> extractConstantMonthLiteral(RowExpression expr)
        {
            if (!(expr instanceof DummyConstantExpression)) {
                return Optional.empty();
            }
            DummyConstantExpression c = (DummyConstantExpression) expr;
            // Assume the constant’s value is a string literal in the format "YYYY-MM".
            return Optional.of(c);
        }
    }


    @Test
    public void testRewriteDateComparison()
    {
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol    = new DummyVariableExpression("ts_col","timestamp");
        RowExpression dateCall = new DummyCallExpression("date","date",Collections.singletonList(tsCol));
        RowExpression dateLit  = new DummyConstantExpression("2020-01-01","date");
        RowExpression equals   = new DummyCallExpression("=","boolean",Arrays.asList(dateCall,dateLit));
        FilterNode filter      = new DummyFilterNode("filterDate", new DummyPlanNode("src"), equals);

        MyResult result = (MyResult) rule.apply(filter, new Captures(){}, new Rule.Context(){});
        Assertions.assertFalse(result.isEmpty(), "rewrite expected");

        DummyCallExpression andCall = (DummyCallExpression) ((FilterNode) result.getPlanNode()).getPredicate();
        Assertions.assertEquals("and", andCall.getDisplayName().toLowerCase());
        Assertions.assertEquals(2, andCall.getArguments().size());

        DummyCallExpression ge = (DummyCallExpression) andCall.getArguments().get(0);
        DummyCallExpression lt = (DummyCallExpression) andCall.getArguments().get(1);
        Assertions.assertEquals(">=", ge.getDisplayName());
        Assertions.assertEquals("<",  lt.getDisplayName());
    }

    @Test
    public void testRewriteSwappedPredicate()
    {
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol    = new DummyVariableExpression("ts_col","timestamp");
        RowExpression dateCall = new DummyCallExpression("date","date",Collections.singletonList(tsCol));
        RowExpression dateLit  = new DummyConstantExpression("2020-01-01","date");
        RowExpression equals   = new DummyCallExpression("=","boolean",Arrays.asList(dateLit,dateCall));
        FilterNode filter      = new DummyFilterNode("filterSwapped", new DummyPlanNode("src"), equals);

        MyResult result = (MyResult) rule.apply(filter, new Captures(){}, new Rule.Context(){});
        Assertions.assertFalse(result.isEmpty(), "rewrite expected");

        DummyCallExpression andCall = (DummyCallExpression) ((FilterNode) result.getPlanNode()).getPredicate();
        Assertions.assertEquals("and", andCall.getDisplayName().toLowerCase());
        Assertions.assertEquals(2, andCall.getArguments().size());
        Assertions.assertEquals(">=", ((DummyCallExpression) andCall.getArguments().get(0)).getDisplayName());
        Assertions.assertEquals("<",  ((DummyCallExpression) andCall.getArguments().get(1)).getDisplayName());
    }

    // -------------------   year()   -------------------

    @Test
    public void testRewriteYearComparison()
    {
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol    = new DummyVariableExpression("ts_col","timestamp");
        RowExpression yearCall = new DummyCallExpression("year","integer",Collections.singletonList(tsCol));
        RowExpression yearLit  = new DummyConstantExpression(1984,"integer");
        RowExpression equals   = new DummyCallExpression("=","boolean",Arrays.asList(yearCall,yearLit));
        FilterNode filter      = new DummyFilterNode("filterYear", new DummyPlanNode("src"), equals);

        MyResult result = (MyResult) rule.apply(filter, new Captures(){}, new Rule.Context(){});
        Assertions.assertFalse(result.isEmpty(), "rewrite expected");

        DummyCallExpression andCall = (DummyCallExpression) ((FilterNode) result.getPlanNode()).getPredicate();
        Assertions.assertEquals("and", andCall.getDisplayName().toLowerCase());
        Assertions.assertEquals(2, andCall.getArguments().size());

        DummyCallExpression ge = (DummyCallExpression) andCall.getArguments().get(0);
        DummyCallExpression lt = (DummyCallExpression) andCall.getArguments().get(1);
        Assertions.assertEquals(">=", ge.getDisplayName());
        Assertions.assertEquals("<",  lt.getDisplayName());

        DummyConstantExpression geConst = (DummyConstantExpression) ge.getArguments().get(1);
        DummyConstantExpression ltConst = (DummyConstantExpression) lt.getArguments().get(1);
        Assertions.assertEquals("1984-01-01 00:00:00.000", geConst.getValue().toString());
        Assertions.assertEquals("1985-01-01 00:00:00.000", ltConst.getValue().toString());
    }

    @Test
    public void testRewriteSwappedYearPredicate()
    {
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol    = new DummyVariableExpression("ts_col","timestamp");
        RowExpression yearCall = new DummyCallExpression("year","integer",Collections.singletonList(tsCol));
        RowExpression yearLit  = new DummyConstantExpression(1984,"integer");
        RowExpression equals   = new DummyCallExpression("=","boolean",Arrays.asList(yearLit,yearCall));
        FilterNode filter      = new DummyFilterNode("filterSwappedYear", new DummyPlanNode("src"), equals);

        MyResult result = (MyResult) rule.apply(filter, new Captures(){}, new Rule.Context(){});
        Assertions.assertFalse(result.isEmpty(), "rewrite expected");

        DummyCallExpression andCall = (DummyCallExpression) ((FilterNode) result.getPlanNode()).getPredicate();
        Assertions.assertEquals("and", andCall.getDisplayName().toLowerCase());
        Assertions.assertEquals(">=", ((DummyCallExpression) andCall.getArguments().get(0)).getDisplayName());
        Assertions.assertEquals("<",  ((DummyCallExpression) andCall.getArguments().get(1)).getDisplayName());
    }

    // -------------------------------
    //  tests for the month() function
    // -------------------------------

    @Test
    public void testRewriteMonthComparison()
    {
        // Create the rule with a dummy manager; now testing month rewriting:
        // month(ts_col) = '2020-05'  -->  ts_col >= '2020-05-01 00:00:00.000'
        //                              and ts_col <  '2020-06-01 00:00:00.000'
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        // Build a predicate: month(ts_col) = '2020-05'
        RowExpression tsCol = new DummyVariableExpression("ts_col", "timestamp");
        RowExpression monthCall = new DummyCallExpression("month", "timestamp", Collections.singletonList(tsCol));
        // Use "varchar" or similar for the literal type if needed.
        RowExpression monthLiteral = new DummyConstantExpression("2020-05", "varchar");
        RowExpression equalsCall = new DummyCallExpression("=", "boolean",
                Arrays.asList(monthCall, monthLiteral));

        // Create a FilterNode with that predicate
        FilterNode filterNode = new DummyFilterNode("filterMonth", new DummyPlanNode("source"), equalsCall);

        // Apply the rule
        Rule.Result result = rule.apply(filterNode, new Captures() {}, new Rule.Context() {});

        // Check that it got a transformation
        Assertions.assertFalse(((MyResult) result).isEmpty(), "Expected a rewrite for month predicate");

        // Get the transformed node
        FilterNode transformed = (FilterNode) ((MyResult) result).getPlanNode();
        RowExpression newPredicate = transformed.getPredicate();

        // The new predicate should be an "and" expression combining two comparisons
        Assertions.assertTrue(newPredicate instanceof DummyCallExpression);
        DummyCallExpression andCall = (DummyCallExpression) newPredicate;
        Assertions.assertEquals("and", andCall.getDisplayName().toLowerCase());
        Assertions.assertEquals(2, andCall.getArguments().size(), "Expected two parts in the AND for month predicate");

        // Check the first part: >= comparison with lower bound "2020-05-01 00:00:00.000"
        RowExpression lowerBound = andCall.getArguments().get(0);
        Assertions.assertTrue(lowerBound instanceof DummyCallExpression);
        DummyCallExpression lowerComparison = (DummyCallExpression) lowerBound;
        Assertions.assertEquals(">=", lowerComparison.getDisplayName());
        RowExpression lowerConstant = lowerComparison.getArguments().get(1);
        Assertions.assertTrue(lowerConstant instanceof DummyConstantExpression);
        DummyConstantExpression lowerConst = (DummyConstantExpression) lowerConstant;
        Assertions.assertEquals("2020-05-01 00:00:00.000", lowerConst.getValue().toString());

        // Check the second part: < comparison with upper bound "2020-06-01 00:00:00.000"
        RowExpression upperBound = andCall.getArguments().get(1);
        Assertions.assertTrue(upperBound instanceof DummyCallExpression);
        DummyCallExpression upperComparison = (DummyCallExpression) upperBound;
        Assertions.assertEquals("<", upperComparison.getDisplayName());
        RowExpression upperConstant = upperComparison.getArguments().get(1);
        Assertions.assertTrue(upperConstant instanceof DummyConstantExpression);
        DummyConstantExpression upperConst = (DummyConstantExpression) upperConstant;
        Assertions.assertEquals("2020-06-01 00:00:00.000", upperConst.getValue().toString());
    }

    @Test
    public void testRewriteSwappedMonthPredicate()
    {
        // Test that the rule applies when the month literal and month() function are swapped:
        // i.e., '2020-05' = month(ts_col)
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol = new DummyVariableExpression("ts_col", "timestamp");
        RowExpression monthCall = new DummyCallExpression("month", "timestamp", Collections.singletonList(tsCol));
        RowExpression monthLiteral = new DummyConstantExpression("2020-05", "varchar");
        // Swap the order: literal comes first
        RowExpression equalsCall = new DummyCallExpression("=", "boolean",
                Arrays.asList(monthLiteral, monthCall));

        FilterNode filterNode = new DummyFilterNode("filterSwappedMonth", new DummyPlanNode("source"), equalsCall);

        Rule.Result result = rule.apply(filterNode, new Captures() {}, new Rule.Context() {});
        Assertions.assertFalse(((MyResult) result).isEmpty(), "Expected a rewrite for swapped month predicate");

        FilterNode transformed = (FilterNode) ((MyResult) result).getPlanNode();
        DummyCallExpression andCall = (DummyCallExpression) transformed.getPredicate();
        Assertions.assertEquals("and", andCall.getDisplayName().toLowerCase());
        Assertions.assertEquals(2, andCall.getArguments().size(), "Expected two parts in the AND for swapped month predicate");
    }

    // -------------------------------
    // Constants for comparison operators
    // -------------------------------
    private static final Operator EQUAL = Operator.EQUAL;
    private static final Operator GREATER_THAN_OR_EQUAL = Operator.GREATER_THAN_OR_EQUAL;
    private static final Operator LESS_THAN = Operator.LESS_THAN;

    // Minimal enum to simulate operator types for testing.
    enum Operator {
        EQUAL("="),
        GREATER_THAN_OR_EQUAL(">="),
        LESS_THAN("<");

        private final String functionName;

        Operator(String functionName)
        {
            this.functionName = functionName;
        }

        public String getFunctionName()
        {
            return functionName;
        }
    }



    // -------------------------------
    // New tests for the hour() function
    // -------------------------------

    @Test
    public void testRewriteHourComparison()
    {
        // hour(ts_col) = '2020-05-12-07'
        //   --> ts_col >= '2020-05-12 07:00:00.000'
        //   AND ts_col <  '2020-05-12 08:00:00.000'
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol= new DummyVariableExpression("ts_col", "timestamp");
        RowExpression hourCall= new DummyCallExpression("hour", "integer", Collections.singletonList(tsCol));
        RowExpression hourLiteral= new DummyConstantExpression("2020-05-12-07", "varchar");
        RowExpression equalsCall = new DummyCallExpression("=", "boolean",
                Arrays.asList(hourCall, hourLiteral));

        FilterNode filterNode = new DummyFilterNode("filterHour", new DummyPlanNode("source"), equalsCall);
        Rule.Result result = rule.apply(filterNode, new Captures() {}, new Rule.Context() {});
        Assertions.assertFalse(((MyResult) result).isEmpty(), "Expected a rewrite for hour predicate");

        DummyFilterNode transformed = (DummyFilterNode) ((MyResult) result).getPlanNode();
        DummyCallExpression andCall = (DummyCallExpression) transformed.getPredicate();
        Assertions.assertEquals("and", andCall.getDisplayName().toLowerCase());
        Assertions.assertEquals(2, andCall.getArguments().size(), "Expected two parts in the AND for hour predicate");

        // --- Lower bound check: ts_col >= '2020-05-12 07:00:00.000'
        DummyCallExpression lowerComp = (DummyCallExpression) andCall.getArguments().get(0);
        Assertions.assertEquals(">=", lowerComp.getDisplayName());
        // verify the constant
        DummyConstantExpression lowerConst = (DummyConstantExpression) lowerComp.getArguments().get(1);
        Assertions.assertEquals("2020-05-12 07:00:00.000", lowerConst.getValue().toString());

        // --- Upper bound check: ts_col < '2020-05-12 08:00:00.000'
        DummyCallExpression upperComp = (DummyCallExpression) andCall.getArguments().get(1);
        Assertions.assertEquals("<", upperComp.getDisplayName());
        DummyConstantExpression upperConst = (DummyConstantExpression) upperComp.getArguments().get(1);
        Assertions.assertEquals("2020-05-12 08:00:00.000", upperConst.getValue().toString());
    }

    @Test
    public void testRewriteSwappedHourPredicate()
    {
        // '2020-05-12-07' = hour(ts_col)
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol= new DummyVariableExpression("ts_col", "timestamp");
        RowExpression hourCall= new DummyCallExpression("hour", "integer", Collections.singletonList(tsCol));
        RowExpression hourLiteral= new DummyConstantExpression("2020-05-12-07", "varchar");
        RowExpression equalsCall= new DummyCallExpression("=", "boolean",
                Arrays.asList(hourLiteral, hourCall));

        FilterNode filterNode = new DummyFilterNode("filterSwappedHour", new DummyPlanNode("source"), equalsCall);
        Rule.Result result= rule.apply(filterNode, new Captures() {}, new Rule.Context() {});
        Assertions.assertFalse(((MyResult) result).isEmpty(), "Expected a rewrite for swapped hour predicate");

        DummyCallExpression andCall = (DummyCallExpression) ((DummyFilterNode) ((MyResult) result).getPlanNode())
                .getPredicate();
        Assertions.assertEquals("and", andCall.getDisplayName().toLowerCase());
        Assertions.assertEquals(2, andCall.getArguments().size(), "Expected two parts in the AND for swapped hour predicate");
    }
}

