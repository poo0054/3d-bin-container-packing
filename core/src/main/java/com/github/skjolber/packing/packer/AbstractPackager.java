package com.github.skjolber.packing.packer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.github.skjolber.packing.api.*;
import com.github.skjolber.packing.deadline.BooleanSupplierBuilder;
import com.github.skjolber.packing.iterator.BinarySearchIterator;

/**
 * Fit boxes into container, i.e. perform bin packing to a single container.
 * <p>
 * Thread-safe implementation.
 */

public abstract class AbstractPackager<P extends PackResult, B extends PackagerResultBuilder<B>>
    implements Packager<B> {

    protected static final EmptyPackResult EMPTY_PACK_RESULT = EmptyPackResult.EMPTY;

    protected final Container[] containers;
    protected final PackResultComparator packResultComparator;

    /**
     * limit the number of calls to get System.currentTimeMillis()
     */
    protected final int checkpointsPerDeadlineCheck;

    /**
     * Constructor
     *
     * @param containers list of containers
     * @param checkpointsPerDeadlineCheck number of deadline checks to skip, before checking again
     */

    public AbstractPackager(List<Container> containers, int checkpointsPerDeadlineCheck,
        PackResultComparator packResultComparator) {
        if (containers.isEmpty()) {
            throw new IllegalArgumentException();
        }
        this.containers = containers.toArray(new Container[containers.size()]);
        if (this.containers.length == 0) {
            throw new RuntimeException();
        }
        this.checkpointsPerDeadlineCheck = checkpointsPerDeadlineCheck;
        this.packResultComparator = packResultComparator;

        long maxVolume = Long.MIN_VALUE;
        long maxWeight = Long.MIN_VALUE;

        for (Container container : containers) {
            // volume
            long boxVolume = container.getVolume();
            if (boxVolume > maxVolume) {
                maxVolume = boxVolume;
            }

            // weight
            long boxWeight = container.getWeight();
            if (boxWeight > maxWeight) {
                maxWeight = boxWeight;
            }
        }
    }

    private static List<Stackable> toBoxes(List<StackableItem> StackableItems, boolean clone) {
        List<Stackable> boxClones = new ArrayList<>(StackableItems.size() * 2);

        for (StackableItem item : StackableItems) {
            Stackable box = item.getStackable();
            boxClones.add(box);
            for (int i = 1; i < item.getCount(); i++) {
                boxClones.add(clone ? box : box.clone());
            }
        }
        return boxClones;
    }

    /**
     * Return a container which holds all the boxes in the argument.
     *
     * @param boxes list of boxes to fit in a container
     * @return null if no match
     */
    public Container pack(List<StackableItem> boxes) {
        return pack(boxes, BooleanSupplierBuilder.NOOP);
    }

    /**
     * Return a container which holds all the boxes in the argument
     *
     * @param boxes list of boxes to fit in a container
     * @param deadline the system time in millis at which the search should be aborted
     * @return list of containers, or null if the deadline was reached / the packages could not be packaged within the
     *         available containers and/or limit
     */

    public Container pack(List<StackableItem> boxes, long deadline) {
        return pack(boxes,
            BooleanSupplierBuilder.builder().withDeadline(deadline, checkpointsPerDeadlineCheck).build());
    }

    public Container pack(List<StackableItem> boxes, BooleanSupplier interrupt) {
        return packImpl(boxes, Arrays.asList(containers), interrupt);
    }

    /**
     * Return a container which holds all the boxes in the argument
     *
     * @param boxes list of boxes to fit in a container
     * @param deadline the system time in millis at which the search should be aborted
     * @param interrupt When true, the computation is interrupted as soon as possible.
     * @return list of containers, or null if the deadline was reached / the packages could not be packaged within the
     *         available containers and/or limit
     */
    public Container pack(List<StackableItem> boxes, long deadline, BooleanSupplier interrupt) {
        return pack(boxes, Arrays.asList(containers), deadline, interrupt);
    }

    /**
     * Return a container which holds all the boxes in the argument
     *
     * @param boxes list of boxes to fit in a container
     * @param containers list of containers
     * @param deadline the system time in milliseconds at which the search should be aborted
     * @return list of containers, or null if the deadline was reached / the packages could not be packaged within the
     *         available containers and/or limit
     */
    public Container pack(List<StackableItem> boxes, List<Container> containers, long deadline) {
        return packImpl(boxes, containers,
            BooleanSupplierBuilder.builder().withDeadline(deadline, checkpointsPerDeadlineCheck).build());
    }

    /**
     * Return a container which holds all the boxes in the argument
     *
     * @param boxes list of boxes to fit in a container
     * @param containers list of containers
     * @param deadline the system time in milliseconds at which the search should be aborted
     * @param interrupt When true, the computation is interrupted as soon as possible.
     * @return list of containers, or null if the deadline was reached / the packages could not be packaged within the
     *         available containers and/or limit
     */
    public Container pack(List<StackableItem> boxes, List<Container> containers, long deadline,
        BooleanSupplier interrupt) {
        return packImpl(boxes, containers, BooleanSupplierBuilder.builder()
            .withDeadline(deadline, checkpointsPerDeadlineCheck).withInterrupt(interrupt).build());
    }

    protected Container packImpl(List<StackableItem> boxes, List<Container> candidateContainers,
        BooleanSupplier interrupt) {
        List<Container> containers = filterByVolumeAndWeight(toBoxes(boxes, false), candidateContainers, 1);

        if (containers.isEmpty()) {
            return null;
        }

        Adapter<P> pack = adapter(boxes, containers, interrupt);

        if (containers.size() <= 2) {
            for (int i = 0; i < containers.size(); i++) {
                if (interrupt.getAsBoolean()) {
                    break;
                }

                P result = pack.attempt(i, null);
                if (result == null) {
                    return null; // timeout, no result
                }
                if (result.containsLastStackable()) {
                    return pack.accept(result);
                }
            }
        } else {
            // perform a binary search among the available containers
            // the list is ranked from most desirable to least.
            PackResult[] results = new PackResult[containers.size()];
            boolean[] checked = new boolean[results.length];

            ArrayList<Integer> containerIndexes = new ArrayList<>(containers.size());
            for (int i = 0; i < containers.size(); i++) {
                containerIndexes.add(i);
            }

            BinarySearchIterator iterator = new BinarySearchIterator();

            search:
            do {
                iterator.reset(containerIndexes.size() - 1, 0);

                P bestResult = null;
                int bestIndex = Integer.MAX_VALUE;

                do {
                    int next = iterator.next();
                    int mid = containerIndexes.get(next);

                    P result = pack.attempt(mid, bestResult);
                    if (result == null) {
                        // timeout
                        // return best result so far, whatever it is
                        break search;
                    }
                    checked[mid] = true;
                    if (result.containsLastStackable()) {
                        results[mid] = result;

                        iterator.lower();

                        if (mid < bestIndex) {
                            bestIndex = mid;
                            bestResult = result;
                        }
                    } else {
                        iterator.higher();
                    }
                    if (interrupt.getAsBoolean()) {
                        break search;
                    }
                } while (iterator.hasNext());

                // halt when we have a result, and checked all containers at the lower indexes
                for (int i = 0; i < containerIndexes.size(); i++) {
                    Integer integer = containerIndexes.get(i);
                    if (results[integer] != null) {
                        // remove end items; we already have a better match
                        while (containerIndexes.size() > i) {
                            containerIndexes.remove(containerIndexes.size() - 1);
                        }
                        break;
                    }

                    // remove item
                    if (checked[integer]) {
                        containerIndexes.remove(i);
                        i--;
                    }
                }
            } while (!containerIndexes.isEmpty());

            // return the best, if any
            for (final PackResult result : results) {
                if (result != null) {
                    return pack.accept((P)result);
                }
            }
        }
        return null;
    }

    /**
     * Return a list of containers which holds all the boxes in the argument
     *
     * @param boxes list of boxes to fit in a container
     * @param limit maximum number of containers
     * @param deadline the system time in milliseconds at which the search should be aborted
     * @return list of containers, or null if the deadline was reached / the packages could not be packaged within the
     *         available containers and/or limit
     */
    public List<Container> packList(List<StackableItem> boxes, int limit, long deadline) {
        return packList(boxes, limit,
            BooleanSupplierBuilder.builder().withDeadline(deadline, checkpointsPerDeadlineCheck).build());
    }

    /**
     * Return a list of containers which holds all the boxes in the argument
     *
     * @param boxes list of boxes to fit in a container
     * @param limit maximum number of containers
     * @param deadline the system time in milliseconds at which the search should be aborted
     * @param interrupt When true, the computation is interrupted as soon as possible.
     * @return list of containers, or null if the deadline was reached / the packages could not be packaged within the
     *         available containers and/or limit
     */
    public List<Container> packList(List<StackableItem> boxes, int limit, long deadline, BooleanSupplier interrupt) {
        return packList(boxes, limit, BooleanSupplierBuilder.builder()
            .withDeadline(deadline, checkpointsPerDeadlineCheck).withInterrupt(interrupt).build());
    }

    public List<Container> packList(List<StackableItem> boxes, int limit) {
        return packList(boxes, limit, BooleanSupplierBuilder.NOOP);
    }

    /**
     * Return a list of containers which holds all the boxes in the argument
     *
     * @param boxes list of boxes to fit in a container
     * @param limit maximum number of containers
     * @param interrupt When true, the computation is interrupted as soon as possible.
     * @return list of containers, or null if the deadline was reached / the packages could not be packaged within the
     *         available containers and/or limit
     */
    public List<Container> packList(List<StackableItem> boxes, int limit, BooleanSupplier interrupt) {
        // 返回一个容器列表，该列表可能会在提供的计数中容纳这些框
        List<Container> containers =
            filterByVolumeAndWeight(toBoxes(boxes, true), Arrays.asList(this.containers), limit);
        if (containers.isEmpty()) {
            return null;
        }

        // 不同实现走不同逻辑 当前为 LAFFAdapter
        Adapter<P> pack = adapter(boxes, containers, interrupt);

        List<Container> containerPackResults = new ArrayList<>();

        // TODO binary search: not as simple as in the single-container use-case; discarding containers would need some
        // kind
        // of criteria which could be trivially calculated, perhaps on volume.
        // 一种可以琐碎计算的标准，也许是在体积上。
        do {
            // 当前最佳的装箱
            P best = null;
            for (int i = 0; i < containers.size(); i++) {
                // 跳过
                if (interrupt.getAsBoolean()) {
                    return null;
                }

                P result = pack.attempt(i, best);
                if (result == null) {
                    return null; // timeout
                }
                if (!result.isEmpty()) {
                    // 是否已经装完了 TODO 可以使用最小的箱子进行装箱
                    if (result.containsLastStackable()) {
                        // will not match any better than this
                        best = result;

                        break;
                    }

                    if (best == null
                        || packResultComparator.compare(best, result) == PackResultComparator.ARGUMENT_2_IS_BETTER) {
                        best = result;
                    }
                }
            }

            if (best == null) {
                // negative result
                return null;
            }

            boolean end = best.containsLastStackable();

            containerPackResults.add(pack.accept(best));

            if (end) {
                // positive result
                return containerPackResults;
            }

        } while (containerPackResults.size() < limit);

        return null;
    }

    /**
     * Return a list of containers which can potentially hold the boxes within the provided count
     *
     * @param boxes list of boxes
     * @param containers list of containers
     * @param count maximum number of possible containers
     * @return list of containers
     */
    private List<Container> filterByVolumeAndWeight(List<Stackable> boxes, List<Container> containers, int count) {
        // 总和
        long volume = 0;

        long weight = 0;

        // 算出总箱子体积 重量
        for (Stackable box : boxes) {
            // volume
            long boxVolume = box.getVolume();
            volume += boxVolume;

            // weight
            long boxWeight = box.getWeight();
            weight += boxWeight;
        }

        List<Container> list = new ArrayList<>(containers.size());

        if (count == 1) {
            // 只有一个箱子
            containers:
            for (Container container : containers) {
                if (container.getMaxLoadVolume() < volume) {
                    continue;
                }
                if (container.getMaxLoadWeight() < weight) {
                    continue;
                }

                for (Stackable box : boxes) {
                    if (!container.canLoad(box)) {
                        continue containers;
                    }
                }
                list.add(container);
            }

        } else {
            // 求出当前 最大容器的重量 体积
            long maxContainerLoadVolume = Long.MIN_VALUE;
            long maxContainerLoadWeight = Long.MIN_VALUE;

            for (Container container : containers) {
                // volume
                long boxVolume = container.getVolume();
                if (boxVolume > maxContainerLoadVolume) {
                    maxContainerLoadVolume = boxVolume;
                }

                // weight
                long boxWeight = container.getMaxLoadWeight();
                if (boxWeight > maxContainerLoadWeight) {
                    maxContainerLoadWeight = boxWeight;
                }
            }

            // 最大箱子都装不下所有 直接返回
            if (maxContainerLoadVolume * count < volume || maxContainerLoadWeight * count < weight) {
                // no containers will work at current count
                return Collections.emptyList();
            }

            // 最小体积 最小重量
            long minVolume = Long.MAX_VALUE;
            long minWeight = Long.MAX_VALUE;

            for (Stackable box : boxes) {
                // volume
                long boxVolume = box.getVolume();
                if (boxVolume < minVolume) {
                    minVolume = boxVolume;
                }

                // weight
                long boxWeight = box.getWeight();
                if (boxWeight < minWeight) {
                    minWeight = boxWeight;
                }
            }

            for (Container container : containers) {
                if (container.getMaxLoadVolume() < minVolume || container.getMaxLoadWeight() < minWeight) {
                    // this container cannot even fit a single box
                    // 这个容器甚至不能装一个盒子
                    continue;
                }

                if (container.getMaxLoadVolume() + maxContainerLoadVolume * (count - 1) < volume
                    || container.getMaxLoadWeight() + maxContainerLoadWeight * (count - 1) < weight) {
                    // this container cannot be used even together with all biggest boxes
                    // 这个容器甚至不能和所有最大的盒子一起使用
                    continue;
                }

                if (!canLoadAtLeastOne(container, boxes)) {
                    continue;
                }
                // 体积和重量都能通过的容器
                list.add(container);
            }
        }

        return list;
    }

    private boolean canLoadAtLeastOne(Container containerBox, List<Stackable> boxes) {
        for (Stackable box : boxes) {
            if (containerBox.canLoad(box)) {
                return true;
            }
        }
        return false;
    }

    protected abstract Adapter<P> adapter(List<StackableItem> boxes, List<Container> containers,
        BooleanSupplier interrupt);

    protected long getMinStackableItemVolume(List<StackableItem> stackables) {
        long minVolume = Integer.MAX_VALUE;
        for (StackableItem stackableItem : stackables) {
            Stackable stackable = stackableItem.getStackable();
            if (stackable.getVolume() < minVolume) {
                minVolume = stackable.getVolume();
            }
        }
        return minVolume;
    }

    protected long getMinStackableItemArea(List<StackableItem> stackables) {
        long minArea = Integer.MAX_VALUE;
        for (StackableItem stackableItem : stackables) {
            Stackable stackable = stackableItem.getStackable();
            if (stackable.getMinimumArea() < minArea) {
                minArea = stackable.getMinimumArea();
            }
        }
        return minArea;
    }

    protected long getMinStackableVolume(List<Stackable> stackables) {
        long minVolume = Integer.MAX_VALUE;
        for (Stackable stackable : stackables) {
            if (stackable.getVolume() < minVolume) {
                minVolume = stackable.getVolume();
            }
        }
        return minVolume;
    }

    protected long getMinStackableArea(List<Stackable> stackables) {
        long minArea = Integer.MAX_VALUE;
        for (Stackable stackable : stackables) {
            if (stackable.getMinimumArea() < minArea) {
                minArea = stackable.getMinimumArea();
            }
        }
        return minArea;
    }

}
