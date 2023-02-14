package com.github.skjolber.packing.packer.laff;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import com.github.skjolber.packing.api.*;
import com.github.skjolber.packing.api.ep.Point3D;
import com.github.skjolber.packing.api.ep.StackValuePointFilter;
import com.github.skjolber.packing.ep.points3d.ExtremePoints3D;
import com.github.skjolber.packing.packer.AbstractPackagerBuilder;
import com.github.skjolber.packing.packer.DefaultPackResult;
import com.github.skjolber.packing.packer.DefaultPackResultComparator;

/**
 * Fit boxes into container, i.e. perform bin packing to a single container. <br>
 * <br>
 * Thread-safe implementation. The input Boxes must however only be used in a single thread at a time.
 */

public class LargestAreaFitFirstPackager extends AbstractLargestAreaFitFirstPackager<Point3D<StackPlacement>> {

    public LargestAreaFitFirstPackager(List<Container> containers, int checkpointsPerDeadlineCheck,
        PackResultComparator packResultComparator,
        LargestAreaFitFirstPackagerConfigurationBuilderFactory<Point3D<StackPlacement>, ?> factory) {
        super(containers, checkpointsPerDeadlineCheck, packResultComparator, factory);
    }

    public static LargestAreaFitFirstPackagerBuilder newBuilder() {
        return new LargestAreaFitFirstPackagerBuilder();
    }

    public DefaultPackResult pack(List<Stackable> stackables, Container targetContainer, BooleanSupplier interrupt) {
        List<Stackable> remainingStackables = new ArrayList<>(stackables);

        ContainerStackValue[] stackValues = targetContainer.getStackValues();

        ContainerStackValue containerStackValue = stackValues[0];

        // 容器中的内部容器
        StackConstraint constraint = containerStackValue.getConstraint();

        LevelStack stack = new LevelStack(containerStackValue);

        // 过滤掉体积
        List<Stackable> scopedStackables = stackables.stream()
            // 过滤体积 重量
            .filter(s -> s.getVolume() <= containerStackValue.getMaxLoadVolume()
                && s.getWeight() <= targetContainer.getMaxLoadWeight())
            // 容器内部的容器
            .filter(s -> constraint == null || constraint.canAccept(s)).collect(Collectors.toList());

        // 关键信息
        ExtremePoints3D<StackPlacement> extremePoints3D = new ExtremePoints3D<>(containerStackValue.getLoadDx(),
            containerStackValue.getLoadDy(), containerStackValue.getLoadDz());
        extremePoints3D.setMinimumAreaAndVolumeLimit(getMinStackableArea(scopedStackables),
            getMinStackableVolume(scopedStackables));

        LargestAreaFitFirstPackagerConfiguration<Point3D<StackPlacement>> configuration = factory.newBuilder()
            .withContainer(targetContainer).withExtremePoints(extremePoints3D).withStack(stack).build();

        StackableFilter firstFilter = configuration.getFirstStackableFilter();
        StackValuePointFilter<Point3D<StackPlacement>> firstStackValuePointComparator =
            configuration.getFirstStackValuePointFilter();

        int levelOffset = 0;

        while (!scopedStackables.isEmpty()) {
            if (interrupt.getAsBoolean()) {
                // fit2d below might have returned due to deadline

                return null;
            }

            // 容器最大重量
            int maxWeight = stack.getFreeWeightLoad();

            // there is only point, spanning the free space in the level
            Point3D<StackPlacement> firstPoint = extremePoints3D.getValue(0);

            int firstIndex = -1;
            // 位置
            StackValue firstStackValue = null;
            // 包括 体积 重量
            Stackable firstBox = null;

            // pick the box with the highest area 挑选面积最高的盒子
            // 优先选择面积最大的盒子 并比较剩余空间最大值是否能装得下
            for (int i = 0; i < scopedStackables.size(); i++) {
                Stackable box = scopedStackables.get(i);
                if (box.getWeight() > maxWeight) {
                    continue;
                }
                if (constraint != null && !constraint.accepts(stack, box)) {
                    continue;
                }
                // 面积比较
                if (firstBox != null && !firstFilter.filter(firstBox, box)) {
                    continue;
                }
                // 试试哪些位置可以摆放 - TODO 可能多个位置都能摆放 但是其中一个能放的更多 无法确定
                for (StackValue stackValue : box.getStackValues()) {
                    // 容器是否能容纳该位置
                    if (!firstPoint.fits3D(stackValue)) {
                        continue;
                    }
                    // 面积 体积比较 选出最大的
                    if (firstStackValue != null && !firstStackValuePointComparator.accept(firstBox, firstPoint,
                        firstStackValue, box, firstPoint, stackValue)) {
                        continue;
                    }
                    // 容器内部容器
                    if (constraint != null && !constraint.supports(stack, box, stackValue, 0, 0, levelOffset)) {
                        continue;
                    }
                    firstIndex = i;
                    firstStackValue = stackValue;
                    firstBox = box;
                    // 当前可以进行装箱

                }
            }

            if (firstIndex == -1) {
                break;
            }
            Stackable stackable = scopedStackables.remove(firstIndex);
            remainingStackables.remove(stackable);

            DefaultContainerStackValue levelStackValue = stack.getContainerStackValue(firstStackValue.getDz());
            Stack levelStack = new DefaultStack();
            stack.add(levelStack);

            StackPlacement first = new StackPlacement(stackable, firstStackValue, 0, 0, 0, -1, -1);

            levelStack.add(first);
            // 剩余体重
            int maxRemainingLevelWeight = levelStackValue.getMaxLoadWeight() - stackable.getWeight();

            extremePoints3D.reset(containerStackValue.getLoadDx(), containerStackValue.getLoadDy(),
                firstStackValue.getDz());
            extremePoints3D.add(0, first);

            StackableFilter nextFilter = configuration.getNextStackableFilter();
            StackValuePointFilter<Point3D<StackPlacement>> nextStackValuePointComparator =
                configuration.getNextStackValuePointFilter();

            while (!extremePoints3D.isEmpty() && maxRemainingLevelWeight > 0 && !scopedStackables.isEmpty()) {
                long maxPointVolume = extremePoints3D.getMaxVolume();
                long maxPointArea = extremePoints3D.getMaxArea();

                int bestPointIndex = -1;
                int bestIndex = -1;
                StackValue bestStackValue = null;
                Stackable bestStackable = null;

                for (int i = 0; i < scopedStackables.size(); i++) {
                    Stackable box = scopedStackables.get(i);
                    if (box.getVolume() > maxPointVolume) {
                        continue;
                    }
                    if (box.getWeight() > maxRemainingLevelWeight) {
                        continue;
                    }
                    if (constraint != null && !constraint.accepts(stack, box)) {
                        continue;
                    }

                    if (bestStackValue != null && !nextFilter.filter(bestStackable, box)) {
                        continue;
                    }
                    for (StackValue stackValue : box.getStackValues()) {
                        if (stackValue.getArea() > maxPointArea) {
                            continue;
                        }
                        if (firstStackValue.getDz() < stackValue.getDz()) {
                            continue;
                        }

                        int currentPointsCount = extremePoints3D.getValueCount();
                        for (int k = 0; k < currentPointsCount; k++) {
                            Point3D<StackPlacement> point3d = extremePoints3D.getValue(k);

                            if (!point3d.fits3D(stackValue)) {
                                continue;
                            }
                            if (bestIndex != -1 && !nextStackValuePointComparator.accept(bestStackable,
                                extremePoints3D.getValue(bestPointIndex), bestStackValue, box, point3d, stackValue)) {
                                continue;
                            }
                            if (constraint != null && !constraint.supports(stack, box, stackValue, point3d.getMinX(),
                                point3d.getMinY(), levelOffset + point3d.getMinZ())) {
                                continue;
                            }
                            bestPointIndex = k;
                            bestIndex = i;
                            bestStackValue = stackValue;
                            bestStackable = box;
                        }
                    }
                }

                if (bestIndex == -1) {
                    break;
                }

                Stackable remove = scopedStackables.remove(bestIndex);
                remainingStackables.remove(remove);

                Point3D<StackPlacement> point = extremePoints3D.getValue(bestPointIndex);

                StackPlacement stackPlacement = new StackPlacement(remove, bestStackValue, point.getMinX(),
                    point.getMinY(), point.getMinZ(), -1, -1);
                levelStack.add(stackPlacement);
                extremePoints3D.add(bestPointIndex, stackPlacement);

                boolean minArea = bestStackValue.getArea() == extremePoints3D.getMinAreaLimit();
                boolean minVolume = extremePoints3D.getMinVolumeLimit() == bestStackable.getVolume();
                if (minArea && minVolume) {
                    extremePoints3D.setMinimumAreaAndVolumeLimit(getMinStackableArea(scopedStackables),
                        getMinStackableVolume(scopedStackables));
                } else if (minArea) {
                    extremePoints3D.setMinimumAreaLimit(getMinStackableArea(scopedStackables));
                } else if (minVolume) {
                    extremePoints3D.setMinimumVolumeLimit(getMinStackableVolume(scopedStackables));
                }

                maxRemainingLevelWeight -= remove.getWeight();
            }

            // move boxes up
            for (StackPlacement stackPlacement : levelStack.getPlacements()) {
                stackPlacement.setZ(levelOffset + stackPlacement.getAbsoluteZ());
            }

            levelOffset += firstStackValue.getDz();

            int remainingDz = containerStackValue.getLoadDz() - levelOffset;
            if (remainingDz == 0) {
                break;
            }
            extremePoints3D.reset(containerStackValue.getLoadDx(), containerStackValue.getLoadDy(), remainingDz);
        }

        return new DefaultPackResult(new DefaultContainer(targetContainer.getId(), targetContainer.getDescription(),
            targetContainer.getVolume(), targetContainer.getEmptyWeight(), stackValues, stack), stack,
            remainingStackables.isEmpty());
    }

    @Override
    public LargestAreaFitFirstPackagerResultBuilder newResultBuilder() {
        return new LargestAreaFitFirstPackagerResultBuilder()
            .withCheckpointsPerDeadlineCheck(checkpointsPerDeadlineCheck).withPackager(this);
    }

    public static class LargestAreaFitFirstPackagerBuilder
        extends AbstractPackagerBuilder<LargestAreaFitFirstPackager, LargestAreaFitFirstPackagerBuilder> {

        private LargestAreaFitFirstPackagerConfigurationBuilderFactory<Point3D<StackPlacement>,
            ?> configurationBuilderFactory;

        public LargestAreaFitFirstPackagerBuilder withConfigurationBuilderFactory(
            LargestAreaFitFirstPackagerConfigurationBuilderFactory<Point3D<StackPlacement>, ?> configurationBuilder) {
            this.configurationBuilderFactory = configurationBuilder;
            return this;
        }

        public LargestAreaFitFirstPackager build() {
            if (containers == null) {
                throw new IllegalStateException("Expected containers");
            }
            if (configurationBuilderFactory == null) {
                configurationBuilderFactory = new DefaultLargestAreaFitFirstPackagerConfigurationBuilderFactory<>();
            }
            if (packResultComparator == null) {
                packResultComparator = new DefaultPackResultComparator();
            }
            return new LargestAreaFitFirstPackager(containers, checkpointsPerDeadlineCheck, packResultComparator,
                configurationBuilderFactory);
        }
    }
}
