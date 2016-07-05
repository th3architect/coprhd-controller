package com.emc.sa.asset.providers;

import java.util.List;

import org.springframework.stereotype.Component;

import com.emc.sa.asset.AssetOptionsContext;
import com.emc.sa.asset.BaseAssetOptionsProvider;
import com.emc.sa.asset.annotation.Asset;
import com.emc.sa.asset.annotation.AssetDependencies;
import com.emc.sa.asset.annotation.AssetNamespace;
import com.emc.vipr.model.catalog.AssetOption;
import com.google.common.collect.Lists;

@Component
@AssetNamespace("vipr")
public class SchedulerProvider extends BaseAssetOptionsProvider {
    
    private static List<AssetOption> RECURRENCE_PATTERN_OPTIONS = Lists.newArrayList(
            newAssetOption("1", "Hours"),
            newAssetOption("2", "Days"),
            newAssetOption("3", "Weeks"),
            newAssetOption("4", "Months"));
    
    private static List<AssetOption> DAY_OF_WEEK_OPTIONS = Lists.newArrayList(
            newAssetOption("1", "Monday"),
            newAssetOption("2", "Tuesday"),
            newAssetOption("3", "Wednesday"),
            newAssetOption("4", "Thursday"),
            newAssetOption("5", "Friday"),
            newAssetOption("6", "Saturday"),
            newAssetOption("7", "Sunday"));

    private static List<AssetOption> NONE_OPTIONS = Lists.newArrayList();
    private static List<AssetOption> ENABLE_OPTIONS = Lists.newArrayList(
             newAssetOption("True", "True"));
    
    @Asset("schedulerEnabled")
    public List<AssetOption> getSchedulerEnabledOptions(AssetOptionsContext context) {
        return ENABLE_OPTIONS;
    }
    
    @Asset("schedulerStartTime")
    @AssetDependencies({"schedulerEnabled"})
    public List<AssetOption> getStartTime(AssetOptionsContext context, Boolean schedulerEnabled) {
        if (schedulerEnabled) {
            List<AssetOption> HOUR_OF_DAY_OPTIONS = Lists.newArrayList();
            for (int i = 0 ;i < 24 ; i++) {
                String hour = String.format("%02d:00", i);
                HOUR_OF_DAY_OPTIONS.add(newAssetOption(hour, hour));
            }
            return HOUR_OF_DAY_OPTIONS;
        } else {
            return NONE_OPTIONS;
        }
    }
    
    @Asset("schedulerRecurrenceUnit")
    @AssetDependencies({"schedulerEnabled"})
    public List<AssetOption> getRecurrencePattens(AssetOptionsContext context, Boolean schedulerEnabled) {
        if (schedulerEnabled) {
            return RECURRENCE_PATTERN_OPTIONS;
        } else {
            return NONE_OPTIONS;
        }
    }
    
    @Asset("schedulerRecurrenceDay")
    @AssetDependencies({"schedulerEnabled", "schedulerRecurrenceUnit"})
    public List<AssetOption> getRecurrenceDay(AssetOptionsContext context, Boolean schedulerEnabled, Integer schedulerRecurrenceUnit) {
        if (!schedulerEnabled) {
            return NONE_OPTIONS;
        }
        if (schedulerRecurrenceUnit == 3) {
            return DAY_OF_WEEK_OPTIONS;
        } else if (schedulerRecurrenceUnit == 4) {
            List<AssetOption> DAY_OF_MONTH_OPTIONS = Lists.newArrayList();
            for (int i = 0 ;i < 31 ; i++) {
                String day = String.valueOf(i);
                DAY_OF_MONTH_OPTIONS.add(newAssetOption(day, day));
            }
            return DAY_OF_MONTH_OPTIONS;
        } else {
            return NONE_OPTIONS;
        }
    }
}
