package com.facebook.presto.sql.planner;

import com.facebook.presto.common.type.BooleanType;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.hive.$internal.com.google.common.collect.ImmutableList;
import com.facebook.presto.matching.Captures;
import com.facebook.presto.matching.Pattern;
import com.facebook.presto.metadata.FunctionAndTypeManager;
import com.facebook.presto.spi.function.FunctionHandle;
import com.facebook.presto.spi.function.StandardFunctionResolution;
import com.facebook.presto.spi.plan.FilterNode;
import com.facebook.presto.spi.relation.CallExpression;
import com.facebook.presto.spi.relation.ConstantExpression;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.sql.analyzer.FunctionAndTypeResolver;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.relational.FunctionResolution;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.common.function.OperatorType.EQUAL;
import static com.facebook.presto.common.function.OperatorType.GREATER_THAN_OR_EQUAL;
import static com.facebook.presto.common.function.OperatorType.LESS_THAN;
import static com.facebook.presto.common.type.TimestampType.TIMESTAMP;
import static com.facebook.presto.sql.planner.plan.Patterns.filter;
import static com.facebook.presto.sql.relational.Expressions.comparisonExpression;
import static com.facebook.presto.sql.relational.Expressions.constant;

public class ConvertDateTimestampToTimestampBounds
        implements Rule<FilterNode>
{
    private static final Pattern<FilterNode> PATTERN = filter();
    private static final Type BOOLEAN = BooleanType.BOOLEAN; // Reusable reference to BooleanType

    private final FunctionAndTypeManager functionAndTypeManager;
    private final StandardFunctionResolution functionResolution;

    public ConvertDateTimestampToTimestampBounds(FunctionAndTypeManager functionAndTypeManager)
    {
        this.functionAndTypeManager = functionAndTypeManager;
        this.functionResolution = new FunctionResolution((FunctionAndTypeResolver) functionAndTypeManager);
    }

    @Override
    public Pattern<FilterNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(FilterNode filter, Captures captures, Context context)
    {
        RowExpression originalPredicate = filter.getPredicate();
        RowExpression rewritten = rewritePredicate(originalPredicate);

        if (rewritten.equals(originalPredicate)) {
            return Result.empty();
        }

        FilterNode newFilter = new FilterNode(
                filter.getSourceLocation(),
                filter.getId(),
                filter.getSource(),
                rewritten);

        return Result.ofPlanNode(newFilter);
    }

    private RowExpression rewritePredicate(RowExpression expression)
    {
        // Only handling binary comparison expressions
        if (!(expression instanceof CallExpression)) {
            return expression;
        }

        CallExpression call = (CallExpression) expression;

        // Proceed only for equality comparisons of exactly two arguments
        if (!call.getDisplayName().equals(EQUAL.getFunctionName()) || call.getArguments().size() != 2) {
            return expression;
        }

        RowExpression left = call.getArguments().get(0);
        RowExpression right = call.getArguments().get(1);

        // ===========================
        // 1) date() logic
        // ===========================
        Optional<RowExpression> maybeDateCol = extractDateFunctionArgument(left, right);
        Optional<ConstantExpression> maybeDateLiteral = extractDateLiteral(left, right);

        if (maybeDateCol.isPresent() && maybeDateLiteral.isPresent()) {
            String dateValue = maybeDateLiteral.get().getValue().toString();

            // Lower bound: dateValue + " 00:00:00.000"
            RowExpression lowerBound = comparisonExpression(
                    functionResolution,
                    GREATER_THAN_OR_EQUAL,
                    maybeDateCol.get(),
                    constant(dateValue + " 00:00:00.000", TIMESTAMP)
            );

            // Upper bound: dateValue + 1 day at "00:00:00.000"
            // For example, "1984-01-08" → "1984-01-09"
            RowExpression upperBound = comparisonExpression(
                    functionResolution,
                    LESS_THAN,
                    maybeDateCol.get(),
                    constant(dateValue + "+1 00:00:00.000", TIMESTAMP)
            );

            return createAndExpression(lowerBound, upperBound);
        }

        // ===========================
        // 2) year() logic
        // ===========================
        Optional<RowExpression> maybeYearCol = extractYearFunctionArgument(left, right);
        Optional<ConstantExpression> maybeYearLiteral = extractYearLiteral(left, right);

        if (maybeYearCol.isPresent() && maybeYearLiteral.isPresent()) {
            Object literalVal = maybeYearLiteral.get().getValue();
            long year;
            if (literalVal instanceof Number) {
                year = ((Number) literalVal).longValue();
            }
            else {
                return expression; // Not a valid numeric literal
            }

            // Lower bound: January 1, <year>
            String lowerTimestamp = String.format("%d-01-01 00:00:00.000", year);
            // Upper bound: January 1, <year + 1>
            String upperTimestamp = String.format("%d-01-01 00:00:00.000", year + 1);

            RowExpression lowerBound = comparisonExpression(
                    functionResolution,
                    GREATER_THAN_OR_EQUAL,
                    maybeYearCol.get(),
                    constant(lowerTimestamp, TIMESTAMP)
            );

            RowExpression upperBound = comparisonExpression(
                    functionResolution,
                    LESS_THAN,
                    maybeYearCol.get(),
                    constant(upperTimestamp, TIMESTAMP)
            );

            return createAndExpression(lowerBound, upperBound);
        }

        // ===========================
        // 3) month() logic
        // ===========================
        Optional<RowExpression> maybeMonthCol = extractMonthFunctionArgument(left, right);
        Optional<ConstantExpression> maybeMonthLiteral = extractMonthLiteral(left, right);

        if (maybeMonthCol.isPresent() && maybeMonthLiteral.isPresent()) {
            // Assume literal is "YYYY-MM"
            String monthValue = maybeMonthLiteral.get().getValue().toString();
            String[] parts = monthValue.split("-");
            if (parts.length != 2) {
                return expression;
            }

            int yearPart;
            int monthPart;
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

            // Lower bound: first day of the given year-month
            String lowerTimestamp = String.format("%d-%02d-01 00:00:00.000", yearPart, monthPart);

            // Upper bound: first day of the next month
            String upperTimestamp;
            if (monthPart == 12) {
                // December → next year, January
                upperTimestamp = String.format("%d-01-01 00:00:00.000", yearPart + 1);
            }
            else {
                // Same year → next month
                upperTimestamp = String.format("%d-%02d-01 00:00:00.000", yearPart, monthPart + 1);
            }

            RowExpression lowerBound = comparisonExpression(
                    functionResolution,
                    GREATER_THAN_OR_EQUAL,
                    maybeMonthCol.get(),
                    constant(lowerTimestamp, TIMESTAMP)
            );

            RowExpression upperBound = comparisonExpression(
                    functionResolution,
                    LESS_THAN,
                    maybeMonthCol.get(),
                    constant(upperTimestamp, TIMESTAMP)
            );

            return createAndExpression(lowerBound, upperBound);
        }

        // ===========================
        // 4) hour() logic
        // ===========================
        Optional<RowExpression> maybeHourCol = extractHourFunctionArgument(left, right);
        Optional<ConstantExpression> maybeHourLiteral = extractHourLiteral(left, right);

        if (maybeHourCol.isPresent() && maybeHourLiteral.isPresent()) {
            // We assume the literal is "YYYY-MM-DD-HH"
            String hourValue = maybeHourLiteral.get().getValue().toString();
            String[] parts = hourValue.split("-");
            if (parts.length != 4) {
                return expression; // Not matching "YYYY-MM-DD-HH"
            }

            int yearPart, monthPart, dayPart, hourPart;
            try {
                yearPart = Integer.parseInt(parts[0]);
                monthPart = Integer.parseInt(parts[1]);
                dayPart = Integer.parseInt(parts[2]);
                hourPart = Integer.parseInt(parts[3]);
            }
            catch (NumberFormatException e) {
                return expression;
            }

            // Basic validation (not accounting for real calendar boundaries)
            if (monthPart < 1 || monthPart > 12 ||
                    dayPart < 1   || dayPart > 31  ||
                    hourPart < 0  || hourPart > 23) {
                return expression;
            }

            // Lower bound: the specified hour
            String lowerTimestamp = String.format(
                    "%04d-%02d-%02d %02d:00:00.000",
                    yearPart, monthPart, dayPart, hourPart
            );

            // Upper bound: next hour (rollover if hour == 23 => day+1)
            int nextHour = hourPart + 1;
            int nextDay  = dayPart;
            if (nextHour == 24) {
                nextHour = 0;
                nextDay  = dayPart + 1;
                // Not handling month/year boundaries here
            }

            String upperTimestamp = String.format(
                    "%04d-%02d-%02d %02d:00:00.000",
                    yearPart, monthPart, nextDay, nextHour
            );

            RowExpression lowerBound = comparisonExpression(
                    functionResolution,
                    GREATER_THAN_OR_EQUAL,
                    maybeHourCol.get(),
                    constant(lowerTimestamp, TIMESTAMP)
            );

            RowExpression upperBound = comparisonExpression(
                    functionResolution,
                    LESS_THAN,
                    maybeHourCol.get(),
                    constant(upperTimestamp, TIMESTAMP)
            );

            return createAndExpression(lowerBound, upperBound);
        }

        // If none of the patterns matched, return the expression unchanged.
        return expression;
    }


    private RowExpression createAndExpression(RowExpression left, RowExpression right)
    {
        FunctionHandle andHandle = functionResolution.lookupBuiltInFunction(
                "and",
                ImmutableList.of(BOOLEAN, BOOLEAN));

        // Create an 'and' call combining the two expressions
        return new CallExpression(
                "and",
                andHandle,
                (Type) ImmutableList.of(left, right),
                (List<RowExpression>) BOOLEAN
        );
    }

    // ===========================
    // date() extraction helpers
    // ===========================
    private Optional<RowExpression> extractDateFunctionArgument(RowExpression first, RowExpression second)
    {
        Optional<RowExpression> firstCheck = extractDateFunction(first);
        if (firstCheck.isPresent()) {
            return firstCheck;
        }
        return extractDateFunction(second);
    }

    private Optional<ConstantExpression> extractDateLiteral(RowExpression first, RowExpression second)
    {
        Optional<ConstantExpression> firstCheck = extractConstantDateLiteral(first);
        if (firstCheck.isPresent()) {
            return firstCheck;
        }
        return extractConstantDateLiteral(second);
    }

    private Optional<RowExpression> extractDateFunction(RowExpression expr)
    {
        if (!(expr instanceof CallExpression)) {
            return Optional.empty();
        }
        CallExpression call = (CallExpression) expr;

        boolean isDateFunction = call.getDisplayName().equalsIgnoreCase("date") &&
                call.getType().toString().equalsIgnoreCase("date");

        if (!isDateFunction || call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        // Return the underlying timestamp argument inside date(t)
        return Optional.of(call.getArguments().get(0));
    }

    private Optional<ConstantExpression> extractConstantDateLiteral(RowExpression expr)
    {
        if (!(expr instanceof ConstantExpression)) {
            return Optional.empty();
        }
        return Optional.of((ConstantExpression) expr);
    }

    // ===========================
    // year() extraction helpers
    // ===========================
    private Optional<RowExpression> extractYearFunctionArgument(RowExpression first, RowExpression second)
    {
        Optional<RowExpression> firstCheck = extractYearFunction(first);
        if (firstCheck.isPresent()) {
            return firstCheck;
        }
        return extractYearFunction(second);
    }

    private Optional<ConstantExpression> extractYearLiteral(RowExpression first, RowExpression second)
    {
        Optional<ConstantExpression> firstCheck = extractConstantYearLiteral(first);
        if (firstCheck.isPresent()) {
            return firstCheck;
        }
        return extractConstantYearLiteral(second);
    }

    private Optional<RowExpression> extractYearFunction(RowExpression expr)
    {
        if (!(expr instanceof CallExpression)) {
            return Optional.empty();
        }
        CallExpression call = (CallExpression) expr;
        if (!call.getDisplayName().equalsIgnoreCase("year") || call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        // Return the underlying timestamp argument inside year(t)
        return Optional.of(call.getArguments().get(0));
    }

    private Optional<ConstantExpression> extractConstantYearLiteral(RowExpression expr)
    {
        if (!(expr instanceof ConstantExpression)) {
            return Optional.empty();
        }
        ConstantExpression c = (ConstantExpression) expr;
        Object value = c.getValue();
        if (value instanceof Number) {
            return Optional.of(c);
        }
        return Optional.empty();
    }

    // ===========================
    // month() extraction helpers
    // ===========================
    private Optional<RowExpression> extractMonthFunctionArgument(RowExpression first, RowExpression second)
    {
        Optional<RowExpression> firstCheck = extractMonthFunction(first);
        if (firstCheck.isPresent()) {
            return firstCheck;
        }
        return extractMonthFunction(second);
    }

    private Optional<ConstantExpression> extractMonthLiteral(RowExpression first, RowExpression second)
    {
        Optional<ConstantExpression> firstCheck = extractConstantMonthLiteral(first);
        if (firstCheck.isPresent()) {
            return firstCheck;
        }
        return extractConstantMonthLiteral(second);
    }

    private Optional<RowExpression> extractMonthFunction(RowExpression expr)
    {
        if (!(expr instanceof CallExpression)) {
            return Optional.empty();
        }
        CallExpression call = (CallExpression) expr;
        if (!call.getDisplayName().equalsIgnoreCase("month") || call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        // Return the underlying timestamp argument inside month(t)
        return Optional.of(call.getArguments().get(0));
    }

    private Optional<ConstantExpression> extractConstantMonthLiteral(RowExpression expr)
    {
        if (!(expr instanceof ConstantExpression)) {
            return Optional.empty();
        }
        return Optional.of((ConstantExpression) expr);
    }

    // ===========================
    // hour() extraction helpers
    // ===========================
    private Optional<RowExpression> extractHourFunctionArgument(RowExpression first, RowExpression second)
    {
        Optional<RowExpression> leftCheck = extractHourFunction(first);
        if (leftCheck.isPresent()) {
            return leftCheck;
        }
        return extractHourFunction(second);
    }

    private Optional<ConstantExpression> extractHourLiteral(RowExpression first, RowExpression second)
    {
        Optional<ConstantExpression> leftCheck = extractConstantHourLiteral(first);
        if (leftCheck.isPresent()) {
            return leftCheck;
        }
        return extractConstantHourLiteral(second);
    }

    private Optional<RowExpression> extractHourFunction(RowExpression expr)
    {
        if (!(expr instanceof CallExpression)) {
            return Optional.empty();
        }
        CallExpression call = (CallExpression) expr;
        // Must be hour(...) with at least 1 arg
        if (!call.getDisplayName().equalsIgnoreCase("hour") || call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        // hour(t) → return "t"
        return Optional.of(call.getArguments().get(0));
    }

    private Optional<ConstantExpression> extractConstantHourLiteral(RowExpression expr)
    {
        if (!(expr instanceof ConstantExpression)) {
            return Optional.empty();
        }
        return Optional.of((ConstantExpression) expr);
    }
}
