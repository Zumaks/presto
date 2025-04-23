/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */




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

    interface RowExpressionVisitor<R, C>
    {
        R visitCall(DummyCallExpression call, C context);
        R visitConstant(DummyConstantExpression literal, C context);
        R visitVariableReference(DummyVariableExpression variable, C context);
    }

    interface RowExpression
    {
        <R, C> R accept(RowExpressionVisitor<R, C> visitor, C context);

        Type getType();
    }

    interface Type
    {
        String getDisplayName();
    }

    interface PlanNode
    {
        String getId();
        List<PlanNode> getSources();
        <R, C> R accept(PlanVisitor<R, C> visitor, C context);
    }


    interface PlanVisitor<R, C>
    {
        R visitPlan(PlanNode node, C context);
    }


    interface FilterNode extends PlanNode
    {
        RowExpression getPredicate();
    }


    interface Rule<T extends PlanNode>
    {
        Pattern getPattern();

        Result apply(T node, Captures captures, Context context);


        interface Context {}
        interface Result {}
    }

    interface Captures {}

    interface Pattern {}


    static Pattern filter()
    {
        return new Pattern() {};
    }

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
    {}

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


    static final class MyResult implements Rule.Result
    {
        private final PlanNode planNode;

        private MyResult(PlanNode planNode)
        {
            this.planNode = planNode;
        }

        static MyResult empty()
        {
            return new MyResult(null);
        }

        static MyResult ofPlanNode(PlanNode node)
        {
            return new MyResult(node);
        }

        boolean isEmpty()
        {
            return planNode == null;
        }

        PlanNode getPlanNode()
        {
            if (planNode == null) {
                throw new IllegalStateException("Result is empty");
            }
            return planNode;
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


    /**
     * Minimal version of the rule that checks for
     *    date(ts_col) = DATE 'yyyy-mm-dd'
     * and rewrites to a pair of comparisons on timestamps.
     * <p>
     * Extended to also support:
     *    year(ts_col) = <numeric literal>
     * and
     *    month(ts_col) = 'YYYY-MM'
     * which will be rewritten to timestamp comparisons for the given time period.
     */
    static class ConvertDateTimestampToTimestampBounds implements Rule<FilterNode>
    {
        private static final Pattern PATTERN = filter();

        private final FunctionAndTypeManager functionAndTypeManager;

        public ConvertDateTimestampToTimestampBounds(FunctionAndTypeManager functionAndTypeManager)
        {
            this.functionAndTypeManager = functionAndTypeManager;
        }

        @Override
        public Pattern getPattern()
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

            if (!(expression instanceof DummyCallExpression)) {
                return expression;
            }

            DummyCallExpression call = (DummyCallExpression) expression;


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
        MyResult result = getResult();
        Assertions.assertFalse(result.isEmpty(), "rewrite expected");

        DummyCallExpression andCall = (DummyCallExpression) ((FilterNode) result.getPlanNode()).getPredicate();
        Assertions.assertEquals("and", andCall.getDisplayName().toLowerCase());
        Assertions.assertEquals(2, andCall.getArguments().size());

        DummyCallExpression ge = (DummyCallExpression) andCall.getArguments().get(0);
        DummyCallExpression lt = (DummyCallExpression) andCall.getArguments().get(1);
        Assertions.assertEquals(">=", ge.getDisplayName());
        Assertions.assertEquals("<",  lt.getDisplayName());
    }

    private static MyResult getResult() {
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol    = new DummyVariableExpression("ts_col","timestamp");
        RowExpression dateCall = new DummyCallExpression("date","date",Collections.singletonList(tsCol));
        RowExpression dateLit  = new DummyConstantExpression("2020-01-01","date");
        RowExpression equals   = new DummyCallExpression("=","boolean",Arrays.asList(dateCall,dateLit));
        FilterNode filter      = new DummyFilterNode("filterDate", new DummyPlanNode("src"), equals);

        return (MyResult) rule.apply(filter, new Captures(){}, new Rule.Context(){});
    }

    @Test
    public void testRewriteSwappedPredicate()
    {
        MyResult result = getMyResult();
        Assertions.assertFalse(result.isEmpty(), "rewrite expected");

        DummyCallExpression andCall = (DummyCallExpression) ((FilterNode) result.getPlanNode()).getPredicate();
        Assertions.assertEquals("and", andCall.getDisplayName().toLowerCase());
        Assertions.assertEquals(2, andCall.getArguments().size());
        Assertions.assertEquals(">=", ((DummyCallExpression) andCall.getArguments().get(0)).getDisplayName());
        Assertions.assertEquals("<",  ((DummyCallExpression) andCall.getArguments().get(1)).getDisplayName());
    }

    private static MyResult getMyResult() {
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol    = new DummyVariableExpression("ts_col","timestamp");
        RowExpression dateCall = new DummyCallExpression("date","date",Collections.singletonList(tsCol));
        RowExpression dateLit  = new DummyConstantExpression("2020-01-01","date");
        RowExpression equals   = new DummyCallExpression("=","boolean",Arrays.asList(dateLit,dateCall));
        FilterNode filter      = new DummyFilterNode("filterSwapped", new DummyPlanNode("src"), equals);

        return (MyResult) rule.apply(filter, new Captures(){}, new Rule.Context(){});
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
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol      = new DummyVariableExpression("ts_col", "timestamp");
        RowExpression monthCall  = new DummyCallExpression("month", "integer",
                Collections.singletonList(tsCol));
        RowExpression monthLit   = new DummyConstantExpression("2020-05", "varchar");
        RowExpression equalsCall = new DummyCallExpression("=", "boolean",
                Arrays.asList(monthCall, monthLit));

        FilterNode filter = new DummyFilterNode("filterMonth", new DummyPlanNode("src"), equalsCall);
        MyResult result   = (MyResult) rule.apply(filter, new Captures(){}, new Rule.Context(){});
        Assertions.assertFalse(result.isEmpty(), "rewrite expected");

        /* ───── verify predicate structure ───── */
        RowExpression predicate = ((FilterNode) result.getPlanNode()).getPredicate();
        Assertions.assertInstanceOf(SpecialFormExpression.class, predicate);
        SpecialFormExpression andForm = (SpecialFormExpression) predicate;
        Assertions.assertEquals(SpecialFormExpression.Form.AND, andForm.getForm());
        Assertions.assertEquals(2, andForm.getArguments().size());

        /* child-0 : ts_col >= '2020-05-01 00:00:00.000' */
        CallExpression ge = (CallExpression) andForm.getArguments().get(0);
        Assertions.assertEquals(OperatorType.GREATER_THAN_OR_EQUAL.name(), ge.getDisplayName());
        ConstantExpression geConst = (ConstantExpression) ge.getArguments().get(1);
        Assertions.assertEquals("2020-05-01 00:00:00.000", geConst.getValue().toString());

        /* child-1 : ts_col <  '2020-06-01 00:00:00.000' */
        CallExpression lt = (CallExpression) andForm.getArguments().get(1);
        Assertions.assertEquals(OperatorType.LESS_THAN.name(), lt.getDisplayName());
        ConstantExpression ltConst = (ConstantExpression) lt.getArguments().get(1);
        Assertions.assertEquals("2020-06-01 00:00:00.000", ltConst.getValue().toString());
    }

    @Test
    public void testRewriteSwappedMonthPredicate()
    {
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol     = new DummyVariableExpression("ts_col", "timestamp");
        RowExpression monthCall = new DummyCallExpression("month", "integer",
                Collections.singletonList(tsCol));
        RowExpression monthLit  = new DummyConstantExpression("2020-05", "varchar");
        RowExpression equalsCall = new DummyCallExpression("=", "boolean",
                Arrays.asList(monthLit, monthCall));

        FilterNode filter = new DummyFilterNode("filterSwappedMonth", new DummyPlanNode("src"), equalsCall);
        MyResult result   = (MyResult) rule.apply(filter, new Captures(){}, new Rule.Context(){});
        Assertions.assertFalse(result.isEmpty(), "rewrite expected");

        SpecialFormExpression andForm =
                (SpecialFormExpression) ((FilterNode) result.getPlanNode()).getPredicate();
        Assertions.assertEquals(SpecialFormExpression.Form.AND, andForm.getForm());
        Assertions.assertEquals(2, andForm.getArguments().size());

        Assertions.assertEquals(OperatorType.GREATER_THAN_OR_EQUAL.name(),
                ((CallExpression) andForm.getArguments().get(0)).getDisplayName());
        Assertions.assertEquals(OperatorType.LESS_THAN.name(),
                ((CallExpression) andForm.getArguments().get(1)).getDisplayName());
    }

    // -------------------------------
    // Constants for comparison operators
    // -------------------------------
    private static final Operator EQUAL = Operator.EQUAL;

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
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol     = new DummyVariableExpression("ts_col", "timestamp");
        RowExpression hourCall  = new DummyCallExpression("hour", "integer",
                Collections.singletonList(tsCol));
        RowExpression hourLit   = new DummyConstantExpression("2020-05-12-07", "varchar");
        RowExpression equals    = new DummyCallExpression("=", "boolean",
                Arrays.asList(hourCall, hourLit));

        FilterNode filter = new DummyFilterNode("filterHour", new DummyPlanNode("src"), equals);
        MyResult result   = (MyResult) rule.apply(filter, new Captures(){}, new Rule.Context(){});
        Assertions.assertFalse(result.isEmpty(), "rewrite expected");

        SpecialFormExpression andForm =
                (SpecialFormExpression) ((FilterNode) result.getPlanNode()).getPredicate();
        Assertions.assertEquals(SpecialFormExpression.Form.AND, andForm.getForm());
        Assertions.assertEquals(2, andForm.getArguments().size());

        CallExpression ge = (CallExpression) andForm.getArguments().get(0);
        Assertions.assertEquals(OperatorType.GREATER_THAN_OR_EQUAL.name(), ge.getDisplayName());
        ConstantExpression geConst = (ConstantExpression) ge.getArguments().get(1);
        Assertions.assertEquals("2020-05-12 07:00:00.000", geConst.getValue().toString());

        CallExpression lt = (CallExpression) andForm.getArguments().get(1);
        Assertions.assertEquals(OperatorType.LESS_THAN.name(), lt.getDisplayName());
        ConstantExpression ltConst = (ConstantExpression) lt.getArguments().get(1);
        Assertions.assertEquals("2020-05-12 08:00:00.000", ltConst.getValue().toString());
    }

    @Test
    public void testRewriteSwappedHourPredicate()
    {
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol    = new DummyVariableExpression("ts_col", "timestamp");
        RowExpression hourCall = new DummyCallExpression("hour", "integer",
                Collections.singletonList(tsCol));
        RowExpression hourLit  = new DummyConstantExpression("2020-05-12-07", "varchar");
        RowExpression equals   = new DummyCallExpression("=", "boolean",
                Arrays.asList(hourLit, hourCall));

        FilterNode filter = new DummyFilterNode("filterSwappedHour", new DummyPlanNode("src"), equals);
        MyResult result   = (MyResult) rule.apply(filter, new Captures(){}, new Rule.Context(){});
        Assertions.assertFalse(result.isEmpty(), "rewrite expected");

        SpecialFormExpression andForm =
                (SpecialFormExpression) ((FilterNode) result.getPlanNode()).getPredicate();
        Assertions.assertEquals(SpecialFormExpression.Form.AND, andForm.getForm());
        Assertions.assertEquals(2, andForm.getArguments().size());
        Assertions.assertEquals(OperatorType.GREATER_THAN_OR_EQUAL.name(),
                ((CallExpression) andForm.getArguments().get(0)).getDisplayName());
        Assertions.assertEquals(OperatorType.LESS_THAN.name(),
                ((CallExpression) andForm.getArguments().get(1)).getDisplayName());
    }

    // ---------------------------------------------------------------------
    // date_trunc() rule change testing
    // ---------------------------------------------------------------------

    @Test
    public void testRewriteDateTruncComparison()
    {
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol  = new DummyVariableExpression("ts_col", "timestamp");
        RowExpression unit   = new DummyConstantExpression("day", "varchar");  // first arg
        RowExpression dtCall = new DummyCallExpression("date_trunc", "timestamp",
                Arrays.asList(unit, tsCol));

        RowExpression tsLit  = new DummyConstantExpression("2024-03-12 00:00:00.000", "timestamp");
        RowExpression equals = new DummyCallExpression("=", "boolean",
                Arrays.asList(dtCall, tsLit));

        FilterNode filter = new DummyFilterNode("filterDt", new DummyPlanNode("src"), equals);
        MyResult result   = (MyResult) rule.apply(filter, new Captures(){}, new Rule.Context(){});
        Assertions.assertFalse(result.isEmpty(), "rewrite expected");

        SpecialFormExpression andForm =
                (SpecialFormExpression) ((FilterNode) result.getPlanNode()).getPredicate();
        Assertions.assertEquals(SpecialFormExpression.Form.AND, andForm.getForm());

        CallExpression ge = (CallExpression) andForm.getArguments().get(0);
        Assertions.assertEquals("2024-03-12 00:00:00.000",
                ((ConstantExpression) ge.getArguments().get(1)).getValue().toString());

        CallExpression lt = (CallExpression) andForm.getArguments().get(1);
        Assertions.assertEquals("2024-03-13 00:00:00.000",
                ((ConstantExpression) lt.getArguments().get(1)).getValue().toString());
    }


    @Test
    public void testRewriteSwappedDateTruncPredicate()
    {
        ConvertDateTimestampToTimestampBounds rule =
                new ConvertDateTimestampToTimestampBounds(new DummyFunctionAndTypeManager());

        RowExpression tsCol  = new DummyVariableExpression("ts_col", "timestamp");
        RowExpression unit   = new DummyConstantExpression("day", "varchar");
        RowExpression dtCall = new DummyCallExpression("date_trunc", "timestamp",
                Arrays.asList(unit, tsCol));
        RowExpression tsLit  = new DummyConstantExpression("2024-03-12 00:00:00.000", "timestamp");

        RowExpression equals = new DummyCallExpression("=", "boolean",
                Arrays.asList(tsLit, dtCall));

        FilterNode filter = new DummyFilterNode("filterSwappedDt", new DummyPlanNode("src"), equals);
        MyResult result   = (MyResult) rule.apply(filter, new Captures(){}, new Rule.Context(){});
        Assertions.assertFalse(result.isEmpty(), "rewrite expected");

        SpecialFormExpression andForm =
                (SpecialFormExpression) ((FilterNode) result.getPlanNode()).getPredicate();
        Assertions.assertEquals(SpecialFormExpression.Form.AND, andForm.getForm());
        Assertions.assertEquals(2, andForm.getArguments().size());
        Assertions.assertEquals(OperatorType.GREATER_THAN_OR_EQUAL.name(),
                ((CallExpression) andForm.getArguments().get(0)).getDisplayName());
        Assertions.assertEquals(OperatorType.LESS_THAN.name(),
                ((CallExpression) andForm.getArguments().get(1)).getDisplayName());
    }
























}

