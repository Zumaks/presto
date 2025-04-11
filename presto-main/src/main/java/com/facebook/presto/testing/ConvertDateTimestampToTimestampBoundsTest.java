package com.facebook.presto.testing;

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
        private final String displayName; // e.g. "=", "date", "and", "year"
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
     * which will be rewritten to timestamp comparisons for the given year.
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

            // If no rewrite occurred, return empty
            if (rewritten.equals(originalPredicate)) {
                return MyResult.empty();
            }

            // Otherwise return a new FilterNode with the rewritten predicate
            FilterNode newFilter = new DummyFilterNode(filter.getId(), filter.getSources().get(0), rewritten);
            return MyResult.ofPlanNode(newFilter);
        }

        private RowExpression rewritePredicate(RowExpression expression)
        {
            // Must be a call expression
            if (!(expression instanceof DummyCallExpression)) {
                return expression;
            }
            DummyCallExpression call = (DummyCallExpression) expression;

            // Must be an "=" call with exactly two arguments
            if (!"=".equals(call.getDisplayName()) || call.getArguments().size() != 2) {
                return expression;
            }

            RowExpression left = call.getArguments().get(0);
            RowExpression right = call.getArguments().get(1);

            // Attempt to match a date(...) = DATE literal first...
            Optional<RowExpression> maybeCol = extractDateCall(left, right);
            Optional<DummyConstantExpression> maybeLiteral = extractDateLiteral(left, right);

            if (maybeCol.isPresent() && maybeLiteral.isPresent()) {
                String dateValue = maybeLiteral.get().getValue().toString();

                RowExpression lowerBound = new DummyCallExpression(
                        ">=", "boolean",
                        Arrays.asList(
                                maybeCol.get(),
                                new DummyConstantExpression(dateValue + " 00:00:00.000", "timestamp")));

                RowExpression upperBound = new DummyCallExpression(
                        "<", "boolean",
                        Arrays.asList(
                                maybeCol.get(),
                                new DummyConstantExpression(dateValue + "+1 00:00:00.000", "timestamp")));

                return new DummyCallExpression("and", "boolean", Arrays.asList(lowerBound, upperBound));
            }

            // Otherwise, attempt to match a year(...) = <numeric literal>
            Optional<RowExpression> maybeYearCol = extractYearCall(left, right);
            Optional<DummyConstantExpression> maybeYearLiteral = extractYearLiteral(left, right);

            if (maybeYearCol.isPresent() && maybeYearLiteral.isPresent()) {
                Object literalVal = maybeYearLiteral.get().getValue();
                long year;
                if (literalVal instanceof Number) {
                    year = ((Number) literalVal).longValue();
                }
                else {
                    return expression;
                }
                // Build boundaries for the year:
                // Lower bound: year-01-01 00:00:00.000
                // Upper bound: (year+1)-01-01 00:00:00.000
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

            return expression;
        }

        private Optional<RowExpression> extractDateCall(RowExpression first, RowExpression second)
        {
            if (isDateFunction(first)) {
                return Optional.of(((DummyCallExpression) first).getArguments().get(0));
            }
            if (isDateFunction(second)) {
                return Optional.of(((DummyCallExpression) second).getArguments().get(0));
            }
            return Optional.empty();
        }

        private boolean isDateFunction(RowExpression expr)
        {
            if (!(expr instanceof DummyCallExpression)) {
                return false;
            }
            DummyCallExpression call = (DummyCallExpression) expr;
            return "date".equalsIgnoreCase(call.getDisplayName()) &&
                    "date".equalsIgnoreCase(call.getType().getDisplayName()) &&
                    !call.getArguments().isEmpty();
        }

        private Optional<DummyConstantExpression> extractDateLiteral(RowExpression first, RowExpression second)
        {
            if ((first instanceof DummyConstantExpression) && isDateType((DummyConstantExpression) first)) {
                return Optional.of((DummyConstantExpression) first);
            }
            if ((second instanceof DummyConstantExpression) && isDateType((DummyConstantExpression) second)) {
                return Optional.of((DummyConstantExpression) second);
            }
            return Optional.empty();
        }

        private boolean isDateType(DummyConstantExpression expr)
        {
            return "date".equalsIgnoreCase(expr.getType().getDisplayName());
        }

        private Optional<RowExpression> extractYearCall(RowExpression first, RowExpression second)
        {
            if (isYearFunction(first)) {
                return Optional.of(((DummyCallExpression) first).getArguments().get(0));
            }
            if (isYearFunction(second)) {
                return Optional.of(((DummyCallExpression) second).getArguments().get(0));
            }
            return Optional.empty();
        }

        private boolean isYearFunction(RowExpression expr)
        {
            if (!(expr instanceof DummyCallExpression)) {
                return false;
            }
            DummyCallExpression call = (DummyCallExpression) expr;
            return "year".equalsIgnoreCase(call.getDisplayName()) &&
                    !call.getArguments().isEmpty();
        }

        private Optional<DummyConstantExpression> extractYearLiteral(RowExpression first, RowExpression second)
        {
            if ((first instanceof DummyConstantExpression) && isYearLiteral((DummyConstantExpression) first)) {
                return Optional.of((DummyConstantExpression) first);
            }
            if ((second instanceof DummyConstantExpression) && isYearLiteral((DummyConstantExpression) second)) {
                return Optional.of((DummyConstantExpression) second);
            }
            return Optional.empty();
        }

        private boolean isYearLiteral(DummyConstantExpression expr)
        {
            return "integer".equalsIgnoreCase(expr.getType().getDisplayName());
        }
    }

    //
    // -------------------------------------------------------------------
    //  7) Actual JUnit tests exercising the rule
    // -------------------------------------------------------------------
    //

    @Test
    public void testRewriteDateComparison()
    {
        // Create the rule with a dummy manager
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        // Build a predicate: date(ts_col) = DATE '2020-01-01'
        RowExpression tsCol = new DummyVariableExpression("ts_col", "timestamp");
        RowExpression dateCall = new DummyCallExpression("date", "date", Collections.singletonList(tsCol));
        RowExpression dateLiteral = new DummyConstantExpression("2020-01-01", "date");
        RowExpression equalsCall = new DummyCallExpression("=", "boolean",
                Arrays.asList(dateCall, dateLiteral));

        // Create a FilterNode with that predicate
        FilterNode filterNode = new DummyFilterNode("filter1", new DummyPlanNode("source"), equalsCall);

        // Apply the rule
        Rule.Result result = rule.apply(filterNode, new Captures() {}, new Rule.Context() {});

        // Check that we got a transformation
        Assertions.assertFalse(((MyResult) result).isEmpty(), "Expected a rewrite");

        // Get the transformed node
        FilterNode transformed = (FilterNode) ((MyResult) result).getPlanNode();
        RowExpression newPredicate = transformed.getPredicate();

        // The new predicate should be "and(..., ...)"
        Assertions.assertTrue(newPredicate instanceof DummyCallExpression);
        DummyCallExpression andCall = (DummyCallExpression) newPredicate;
        Assertions.assertEquals("and", andCall.getDisplayName().toLowerCase());
        Assertions.assertEquals(2, andCall.getArguments().size(), "Expected two parts in the AND");

        // Check the first part: >=
        RowExpression lowerBound = andCall.getArguments().get(0);
        Assertions.assertTrue(lowerBound instanceof DummyCallExpression);
        Assertions.assertEquals(">=", ((DummyCallExpression) lowerBound).getDisplayName());

        // Check the second part: <
        RowExpression upperBound = andCall.getArguments().get(1);
        Assertions.assertTrue(upperBound instanceof DummyCallExpression);
        Assertions.assertEquals("<", ((DummyCallExpression) upperBound).getDisplayName());
    }

    @Test
    public void testRewriteSwappedPredicate()
    {
        // Test that the rule still applies when the date literal and date function are swapped:
        // DATE '2020-01-01' = date(ts_col)
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol = new DummyVariableExpression("ts_col", "timestamp");
        RowExpression dateCall = new DummyCallExpression("date", "date", Collections.singletonList(tsCol));
        RowExpression dateLiteral = new DummyConstantExpression("2020-01-01", "date");
        // Swap the order: literal comes first
        RowExpression equalsCall = new DummyCallExpression("=", "boolean",
                Arrays.asList(dateLiteral, dateCall));

        FilterNode filterNode = new DummyFilterNode("filterSwapped", new DummyPlanNode("source"), equalsCall);

        Rule.Result result = rule.apply(filterNode, new Captures() {}, new Rule.Context() {});
        Assertions.assertFalse(((MyResult) result).isEmpty(), "Expected a rewrite for swapped order");

        FilterNode transformed = (FilterNode) ((MyResult) result).getPlanNode();
        DummyCallExpression andCall = (DummyCallExpression) transformed.getPredicate();
        Assertions.assertEquals("and", andCall.getDisplayName().toLowerCase());
        Assertions.assertEquals(2, andCall.getArguments().size(), "Expected two parts in the AND");
    }

    // -------------------------------
    // New tests for the year() function
    // -------------------------------

    @Test
    public void testRewriteYearComparison()
    {
        // Create the rule with a dummy manager; now testing year rewriting:
        // year(ts_col) = 1984  -->  ts_col >= '1984-01-01 00:00:00.000'
        //                        and ts_col <  '1985-01-01 00:00:00.000'
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        // Build a predicate: year(ts_col) = 1984
        RowExpression tsCol = new DummyVariableExpression("ts_col", "timestamp");
        RowExpression yearCall = new DummyCallExpression("year", "integer", Collections.singletonList(tsCol));
        RowExpression yearLiteral = new DummyConstantExpression(1984, "integer");
        RowExpression equalsCall = new DummyCallExpression("=", "boolean",
                Arrays.asList(yearCall, yearLiteral));

        // Create a FilterNode with that predicate
        FilterNode filterNode = new DummyFilterNode("filterYear", new DummyPlanNode("source"), equalsCall);

        // Apply the rule
        Rule.Result result = rule.apply(filterNode, new Captures() {}, new Rule.Context() {});

        // Check that it got a transformation
        Assertions.assertFalse(((MyResult) result).isEmpty(), "Expected a rewrite for year predicate");

        // Get the transformed node
        FilterNode transformed = (FilterNode) ((MyResult) result).getPlanNode();
        RowExpression newPredicate = transformed.getPredicate();

        // The new predicate should be an "and" expression combining two comparisons
        Assertions.assertTrue(newPredicate instanceof DummyCallExpression, "New predicate should be a call expression");
        DummyCallExpression andCall = (DummyCallExpression) newPredicate;
        Assertions.assertEquals("and", andCall.getDisplayName().toLowerCase());
        Assertions.assertEquals(2, andCall.getArguments().size(), "Expected two parts in the AND for year predicate");

        // Check the first part: >= comparison
        RowExpression lowerBound = andCall.getArguments().get(0);
        Assertions.assertTrue(lowerBound instanceof DummyCallExpression, "Lower bound should be a call expression");
        DummyCallExpression lowerComparison = (DummyCallExpression) lowerBound;
        Assertions.assertEquals(">=", lowerComparison.getDisplayName());

        // The right-hand side of the lower comparison should be a constant with value "1984-01-01 00:00:00.000"
        RowExpression lowerConstant = lowerComparison.getArguments().get(1);
        Assertions.assertTrue(lowerConstant instanceof DummyConstantExpression);
        DummyConstantExpression lowerConst = (DummyConstantExpression) lowerConstant;
        Assertions.assertEquals("1984-01-01 00:00:00.000", lowerConst.getValue().toString());

        // Check the second part: < comparison
        RowExpression upperBound = andCall.getArguments().get(1);
        Assertions.assertTrue(upperBound instanceof DummyCallExpression, "Upper bound should be a call expression");
        DummyCallExpression upperComparison = (DummyCallExpression) upperBound;
        Assertions.assertEquals("<", upperComparison.getDisplayName());

        // The right-hand side of the upper comparison should be a constant with value "1985-01-01 00:00:00.000"
        RowExpression upperConstant = upperComparison.getArguments().get(1);
        Assertions.assertTrue(upperConstant instanceof DummyConstantExpression);
        DummyConstantExpression upperConst = (DummyConstantExpression) upperConstant;
        Assertions.assertEquals("1985-01-01 00:00:00.000", upperConst.getValue().toString());
    }

    @Test
    public void testRewriteSwappedYearPredicate()
    {
        // Test that the rule applies when the year literal and year() function are swapped:
        // i.e., 1984 = year(ts_col)
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol = new DummyVariableExpression("ts_col", "timestamp");
        RowExpression yearCall = new DummyCallExpression("year", "integer", Collections.singletonList(tsCol));
        RowExpression yearLiteral = new DummyConstantExpression(1984, "integer");
        // Swap order: literal comes first
        RowExpression equalsCall = new DummyCallExpression("=", "boolean",
                Arrays.asList(yearLiteral, yearCall));

        FilterNode filterNode = new DummyFilterNode("filterSwappedYear", new DummyPlanNode("source"), equalsCall);

        Rule.Result result = rule.apply(filterNode, new Captures() {}, new Rule.Context() {});
        Assertions.assertFalse(((MyResult) result).isEmpty(), "Expected a rewrite for swapped year predicate");

        FilterNode transformed = (FilterNode) ((MyResult) result).getPlanNode();
        DummyCallExpression andCall = (DummyCallExpression) transformed.getPredicate();
        Assertions.assertEquals("and", andCall.getDisplayName().toLowerCase());
        Assertions.assertEquals(2, andCall.getArguments().size(), "Expected two parts in the AND for swapped year predicate");
    }

    @Test
    public void testNonMatchingPredicate()
    {
        // Create the rule
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        // Build a non‐call predicate (just a variable)
        RowExpression nonMatching = new DummyVariableExpression("some_bool_expr", "boolean");
        FilterNode filterNode = new DummyFilterNode("filterNonMatching", new DummyPlanNode("source"), nonMatching);

        // Apply the rule
        Rule.Result result = rule.apply(filterNode, new Captures() {}, new Rule.Context() {});

        // The rule should not rewrite a non‐matching predicate
        Assertions.assertTrue(((MyResult) result).isEmpty(),
                "Expected no rewrite for a non-matching predicate");
    }

    @Test
    public void testInvalidArgumentCountPredicate()
    {
        // Verify that if the equals operator has more than two arguments, no rewrite occurs.
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol = new DummyVariableExpression("ts_col", "timestamp");
        RowExpression dateCall = new DummyCallExpression("date", "date", Collections.singletonList(tsCol));
        RowExpression dateLiteral = new DummyConstantExpression("2020-01-01", "date");

        // Create an equals call with three arguments (which should be considered invalid)
        RowExpression invalidEqualsCall = new DummyCallExpression("=", "boolean",
                Arrays.asList(dateCall, dateLiteral, tsCol));

        FilterNode filterNode = new DummyFilterNode("filterInvalidArgs", new DummyPlanNode("source"), invalidEqualsCall);
        Rule.Result result = rule.apply(filterNode, new Captures() {}, new Rule.Context() {});

        // Expect no rewrite due to invalid argument count
        Assertions.assertTrue(((MyResult) result).isEmpty(),
                "Expected no rewrite for an equals operator with invalid argument count");
    }
}

