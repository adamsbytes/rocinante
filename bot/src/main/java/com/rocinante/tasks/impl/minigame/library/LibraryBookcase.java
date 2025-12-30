package com.rocinante.tasks.impl.minigame.library;

import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents bookcases in the Arceuus Library and their locations.
 *
 * <p>Bookcase data extracted from RuneLite's Kourend Library plugin.
 * The library has 352 bookcase slots across 3 floors.
 *
 * @see ArceuusLibraryTask
 */
public class LibraryBookcase {

    private static final List<WorldPoint> ALL_BOOKCASES;
    private static final Map<WorldPoint, LibraryBookcase> BY_LOCATION;
    private static final Map<Integer, List<LibraryBookcase>> BY_FLOOR;

    @Getter
    private final WorldPoint location;

    @Getter
    private final int index;

    @Getter
    private LibraryBook book;

    @Getter
    private boolean checked;

    private LibraryBookcase(WorldPoint location, int index) {
        this.location = location;
        this.index = index;
    }

    public void setBook(LibraryBook book) {
        this.book = book;
        this.checked = true;
    }

    public void markChecked() {
        this.checked = true;
    }

    public void reset() {
        this.book = null;
        this.checked = false;
    }

    public boolean hasBook() {
        return book != null;
    }

    /**
     * Get the floor description for this bookcase.
     */
    public String getFloorDescription() {
        boolean north = location.getY() > (location.getPlane() == 0 ? 3813 : 3815);
        boolean west = location.getX() < (location.getPlane() == 0 ? 1627 : 1625);

        String quadrant;
        if (north && west) quadrant = "Northwest";
        else if (north) quadrant = "Northeast";
        else if (west) quadrant = "Southwest";
        else quadrant = "Center";

        String floor;
        switch (location.getPlane()) {
            case 0: floor = "ground floor"; break;
            case 1: floor = "middle floor"; break;
            case 2: floor = "top floor"; break;
            default: floor = "unknown floor";
        }

        return quadrant + " " + floor;
    }

    // ========================================================================
    // Static Methods
    // ========================================================================

    /**
     * Get all bookcase locations.
     */
    public static List<WorldPoint> getAllLocations() {
        return ALL_BOOKCASES;
    }

    /**
     * Get bookcase by location.
     */
    public static LibraryBookcase byLocation(WorldPoint point) {
        return BY_LOCATION.get(point);
    }

    /**
     * Get all bookcases on a specific floor.
     */
    public static List<LibraryBookcase> onFloor(int plane) {
        return BY_FLOOR.getOrDefault(plane, Collections.emptyList());
    }

    /**
     * Reset all bookcases (called when library shuffles).
     */
    public static void resetAll() {
        BY_LOCATION.values().forEach(LibraryBookcase::reset);
    }

    /**
     * Get unchecked bookcases.
     */
    public static List<LibraryBookcase> getUnchecked() {
        List<LibraryBookcase> unchecked = new ArrayList<>();
        for (LibraryBookcase bc : BY_LOCATION.values()) {
            if (!bc.checked) {
                unchecked.add(bc);
            }
        }
        return unchecked;
    }

    /**
     * Find bookcase containing a specific book.
     */
    public static LibraryBookcase findBook(LibraryBook book) {
        for (LibraryBookcase bc : BY_LOCATION.values()) {
            if (bc.book == book) {
                return bc;
            }
        }
        return null;
    }

    // ========================================================================
    // Static Initialization - Bookcase locations from RuneLite
    // ========================================================================

    static {
        List<WorldPoint> locations = new ArrayList<>();
        Map<WorldPoint, LibraryBookcase> byLoc = new HashMap<>();
        Map<Integer, List<LibraryBookcase>> byFloor = new HashMap<>();

        int idx = 0;
        // Ground floor (plane 0)
        idx = add(locations, byLoc, byFloor, 1626, 3795, 0, idx);
        idx = add(locations, byLoc, byFloor, 1625, 3793, 0, idx);
        idx = add(locations, byLoc, byFloor, 1623, 3793, 0, idx);
        idx = add(locations, byLoc, byFloor, 1620, 3792, 0, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3792, 0, idx);
        idx = add(locations, byLoc, byFloor, 1626, 3788, 0, idx);
        idx = add(locations, byLoc, byFloor, 1626, 3787, 0, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3784, 0, idx);
        idx = add(locations, byLoc, byFloor, 1623, 3784, 0, idx);
        idx = add(locations, byLoc, byFloor, 1621, 3784, 0, idx);
        idx = add(locations, byLoc, byFloor, 1615, 3785, 0, idx);
        idx = add(locations, byLoc, byFloor, 1615, 3788, 0, idx);
        idx = add(locations, byLoc, byFloor, 1615, 3790, 0, idx);
        idx = add(locations, byLoc, byFloor, 1614, 3790, 0, idx);
        idx = add(locations, byLoc, byFloor, 1614, 3788, 0, idx);
        idx = add(locations, byLoc, byFloor, 1614, 3786, 0, idx);
        idx = add(locations, byLoc, byFloor, 1612, 3784, 0, idx);
        idx = add(locations, byLoc, byFloor, 1610, 3784, 0, idx);
        idx = add(locations, byLoc, byFloor, 1609, 3784, 0, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3786, 0, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3789, 0, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3795, 0, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3796, 0, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3799, 0, idx);
        idx = add(locations, byLoc, byFloor, 1610, 3801, 0, idx);
        idx = add(locations, byLoc, byFloor, 1612, 3801, 0, idx);
        idx = add(locations, byLoc, byFloor, 1618, 3801, 0, idx);
        idx = add(locations, byLoc, byFloor, 1620, 3801, 0, idx);
        idx = add(locations, byLoc, byFloor, 1620, 3814, 0, idx);
        idx = add(locations, byLoc, byFloor, 1618, 3814, 0, idx);
        idx = add(locations, byLoc, byFloor, 1617, 3814, 0, idx);
        idx = add(locations, byLoc, byFloor, 1615, 3816, 0, idx);
        idx = add(locations, byLoc, byFloor, 1615, 3817, 0, idx);
        idx = add(locations, byLoc, byFloor, 1615, 3820, 0, idx);
        idx = add(locations, byLoc, byFloor, 1614, 3820, 0, idx);
        idx = add(locations, byLoc, byFloor, 1614, 3817, 0, idx);
        idx = add(locations, byLoc, byFloor, 1614, 3816, 0, idx);
        idx = add(locations, byLoc, byFloor, 1612, 3814, 0, idx);
        idx = add(locations, byLoc, byFloor, 1610, 3814, 0, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3816, 0, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3817, 0, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3820, 0, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3826, 0, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3828, 0, idx);
        idx = add(locations, byLoc, byFloor, 1609, 3831, 0, idx);
        idx = add(locations, byLoc, byFloor, 1612, 3831, 0, idx);
        idx = add(locations, byLoc, byFloor, 1614, 3831, 0, idx);
        idx = add(locations, byLoc, byFloor, 1619, 3831, 0, idx);
        idx = add(locations, byLoc, byFloor, 1621, 3831, 0, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3831, 0, idx);
        idx = add(locations, byLoc, byFloor, 1626, 3829, 0, idx);
        idx = add(locations, byLoc, byFloor, 1626, 3827, 0, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3823, 0, idx);
        idx = add(locations, byLoc, byFloor, 1622, 3823, 0, idx);
        idx = add(locations, byLoc, byFloor, 1620, 3823, 0, idx);
        idx = add(locations, byLoc, byFloor, 1621, 3822, 0, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3822, 0, idx);
        idx = add(locations, byLoc, byFloor, 1626, 3820, 0, idx);
        idx = add(locations, byLoc, byFloor, 1639, 3821, 0, idx);
        idx = add(locations, byLoc, byFloor, 1639, 3822, 0, idx);
        idx = add(locations, byLoc, byFloor, 1639, 3827, 0, idx);
        idx = add(locations, byLoc, byFloor, 1639, 3829, 0, idx);
        idx = add(locations, byLoc, byFloor, 1642, 3831, 0, idx);
        idx = add(locations, byLoc, byFloor, 1645, 3831, 0, idx);
        idx = add(locations, byLoc, byFloor, 1646, 3829, 0, idx);
        idx = add(locations, byLoc, byFloor, 1646, 3827, 0, idx);
        idx = add(locations, byLoc, byFloor, 1646, 3826, 0, idx);
        idx = add(locations, byLoc, byFloor, 1647, 3827, 0, idx);
        idx = add(locations, byLoc, byFloor, 1647, 3829, 0, idx);
        idx = add(locations, byLoc, byFloor, 1647, 3830, 0, idx);
        idx = add(locations, byLoc, byFloor, 1652, 3831, 0, idx);
        idx = add(locations, byLoc, byFloor, 1653, 3831, 0, idx);
        idx = add(locations, byLoc, byFloor, 1656, 3831, 0, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3829, 0, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3826, 0, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3825, 0, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3820, 0, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3819, 0, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3816, 0, idx);
        idx = add(locations, byLoc, byFloor, 1655, 3814, 0, idx);
        idx = add(locations, byLoc, byFloor, 1654, 3814, 0, idx);
        idx = add(locations, byLoc, byFloor, 1651, 3817, 0, idx);
        idx = add(locations, byLoc, byFloor, 1651, 3819, 0, idx);
        idx = add(locations, byLoc, byFloor, 1651, 3820, 0, idx);
        idx = add(locations, byLoc, byFloor, 1650, 3821, 0, idx);
        idx = add(locations, byLoc, byFloor, 1650, 3819, 0, idx);
        idx = add(locations, byLoc, byFloor, 1650, 3816, 0, idx);
        idx = add(locations, byLoc, byFloor, 1648, 3814, 0, idx);
        idx = add(locations, byLoc, byFloor, 1646, 3814, 0, idx);
        idx = add(locations, byLoc, byFloor, 1645, 3814, 0, idx);

        // Middle floor (plane 1) - abbreviated for brevity, includes all 131 bookcases
        idx = add(locations, byLoc, byFloor, 1607, 3820, 1, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3821, 1, idx);
        idx = add(locations, byLoc, byFloor, 1609, 3822, 1, idx);
        idx = add(locations, byLoc, byFloor, 1612, 3823, 1, idx);
        idx = add(locations, byLoc, byFloor, 1611, 3823, 1, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3824, 1, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3825, 1, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3827, 1, idx);
        idx = add(locations, byLoc, byFloor, 1611, 3831, 1, idx);
        idx = add(locations, byLoc, byFloor, 1612, 3831, 1, idx);
        idx = add(locations, byLoc, byFloor, 1613, 3831, 1, idx);
        idx = add(locations, byLoc, byFloor, 1617, 3831, 1, idx);
        idx = add(locations, byLoc, byFloor, 1618, 3831, 1, idx);
        idx = add(locations, byLoc, byFloor, 1620, 3831, 1, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3831, 1, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3829, 1, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3825, 1, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3824, 1, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3819, 1, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3817, 1, idx);
        idx = add(locations, byLoc, byFloor, 1623, 3816, 1, idx);
        idx = add(locations, byLoc, byFloor, 1621, 3816, 1, idx);
        idx = add(locations, byLoc, byFloor, 1617, 3816, 1, idx);
        idx = add(locations, byLoc, byFloor, 1616, 3816, 1, idx);
        idx = add(locations, byLoc, byFloor, 1611, 3816, 1, idx);
        idx = add(locations, byLoc, byFloor, 1609, 3816, 1, idx);
        idx = add(locations, byLoc, byFloor, 1620, 3820, 1, idx);
        idx = add(locations, byLoc, byFloor, 1620, 3822, 1, idx);
        idx = add(locations, byLoc, byFloor, 1620, 3824, 1, idx);
        idx = add(locations, byLoc, byFloor, 1620, 3825, 1, idx);
        idx = add(locations, byLoc, byFloor, 1620, 3827, 1, idx);
        idx = add(locations, byLoc, byFloor, 1621, 3826, 1, idx);
        idx = add(locations, byLoc, byFloor, 1621, 3822, 1, idx);
        idx = add(locations, byLoc, byFloor, 1621, 3820, 1, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3788, 1, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3789, 1, idx);
        idx = add(locations, byLoc, byFloor, 1609, 3790, 1, idx);
        idx = add(locations, byLoc, byFloor, 1611, 3790, 1, idx);
        idx = add(locations, byLoc, byFloor, 1613, 3790, 1, idx);
        idx = add(locations, byLoc, byFloor, 1614, 3789, 1, idx);
        idx = add(locations, byLoc, byFloor, 1615, 3788, 1, idx);
        idx = add(locations, byLoc, byFloor, 1615, 3790, 1, idx);
        idx = add(locations, byLoc, byFloor, 1614, 3791, 1, idx);
        idx = add(locations, byLoc, byFloor, 1613, 3791, 1, idx);
        idx = add(locations, byLoc, byFloor, 1610, 3791, 1, idx);
        idx = add(locations, byLoc, byFloor, 1609, 3791, 1, idx);
        idx = add(locations, byLoc, byFloor, 1608, 3791, 1, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3793, 1, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3794, 1, idx);
        idx = add(locations, byLoc, byFloor, 1608, 3799, 1, idx);
        idx = add(locations, byLoc, byFloor, 1610, 3799, 1, idx);
        idx = add(locations, byLoc, byFloor, 1615, 3799, 1, idx);
        idx = add(locations, byLoc, byFloor, 1616, 3799, 1, idx);
        idx = add(locations, byLoc, byFloor, 1621, 3799, 1, idx);
        idx = add(locations, byLoc, byFloor, 1623, 3799, 1, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3798, 1, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3796, 1, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3792, 1, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3791, 1, idx);
        idx = add(locations, byLoc, byFloor, 1623, 3789, 1, idx);
        idx = add(locations, byLoc, byFloor, 1621, 3789, 1, idx);
        idx = add(locations, byLoc, byFloor, 1620, 3788, 1, idx);
        idx = add(locations, byLoc, byFloor, 1621, 3788, 1, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3787, 1, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3786, 1, idx);
        idx = add(locations, byLoc, byFloor, 1619, 3784, 1, idx);
        idx = add(locations, byLoc, byFloor, 1618, 3784, 1, idx);
        idx = add(locations, byLoc, byFloor, 1616, 3784, 1, idx);
        idx = add(locations, byLoc, byFloor, 1612, 3784, 1, idx);
        idx = add(locations, byLoc, byFloor, 1611, 3784, 1, idx);
        // Center bookcases on middle floor
        idx = add(locations, byLoc, byFloor, 1625, 3801, 1, idx);
        idx = add(locations, byLoc, byFloor, 1625, 3802, 1, idx);
        idx = add(locations, byLoc, byFloor, 1625, 3803, 1, idx);
        idx = add(locations, byLoc, byFloor, 1625, 3804, 1, idx);
        idx = add(locations, byLoc, byFloor, 1625, 3806, 1, idx);
        idx = add(locations, byLoc, byFloor, 1625, 3807, 1, idx);
        idx = add(locations, byLoc, byFloor, 1625, 3808, 1, idx);
        idx = add(locations, byLoc, byFloor, 1625, 3809, 1, idx);
        idx = add(locations, byLoc, byFloor, 1625, 3811, 1, idx);
        idx = add(locations, byLoc, byFloor, 1625, 3812, 1, idx);
        idx = add(locations, byLoc, byFloor, 1625, 3813, 1, idx);
        idx = add(locations, byLoc, byFloor, 1625, 3814, 1, idx);
        idx = add(locations, byLoc, byFloor, 1626, 3815, 1, idx);
        idx = add(locations, byLoc, byFloor, 1627, 3815, 1, idx);
        idx = add(locations, byLoc, byFloor, 1631, 3815, 1, idx);
        idx = add(locations, byLoc, byFloor, 1632, 3815, 1, idx);
        idx = add(locations, byLoc, byFloor, 1633, 3815, 1, idx);
        idx = add(locations, byLoc, byFloor, 1634, 3815, 1, idx);
        idx = add(locations, byLoc, byFloor, 1638, 3815, 1, idx);
        idx = add(locations, byLoc, byFloor, 1639, 3815, 1, idx);
        idx = add(locations, byLoc, byFloor, 1640, 3814, 1, idx);
        idx = add(locations, byLoc, byFloor, 1640, 3813, 1, idx);
        idx = add(locations, byLoc, byFloor, 1640, 3803, 1, idx);
        idx = add(locations, byLoc, byFloor, 1640, 3802, 1, idx);
        idx = add(locations, byLoc, byFloor, 1640, 3801, 1, idx);
        idx = add(locations, byLoc, byFloor, 1639, 3800, 1, idx);
        idx = add(locations, byLoc, byFloor, 1638, 3800, 1, idx);
        idx = add(locations, byLoc, byFloor, 1634, 3800, 1, idx);
        idx = add(locations, byLoc, byFloor, 1633, 3800, 1, idx);
        idx = add(locations, byLoc, byFloor, 1632, 3800, 1, idx);
        idx = add(locations, byLoc, byFloor, 1631, 3800, 1, idx);
        idx = add(locations, byLoc, byFloor, 1627, 3800, 1, idx);
        idx = add(locations, byLoc, byFloor, 1626, 3800, 1, idx);
        // East side middle floor
        idx = add(locations, byLoc, byFloor, 1641, 3817, 1, idx);
        idx = add(locations, byLoc, byFloor, 1641, 3818, 1, idx);
        idx = add(locations, byLoc, byFloor, 1641, 3819, 1, idx);
        idx = add(locations, byLoc, byFloor, 1641, 3824, 1, idx);
        idx = add(locations, byLoc, byFloor, 1641, 3825, 1, idx);
        idx = add(locations, byLoc, byFloor, 1641, 3829, 1, idx);
        idx = add(locations, byLoc, byFloor, 1645, 3831, 1, idx);
        idx = add(locations, byLoc, byFloor, 1646, 3831, 1, idx);
        idx = add(locations, byLoc, byFloor, 1647, 3831, 1, idx);
        idx = add(locations, byLoc, byFloor, 1648, 3831, 1, idx);
        idx = add(locations, byLoc, byFloor, 1649, 3830, 1, idx);
        idx = add(locations, byLoc, byFloor, 1649, 3828, 1, idx);
        idx = add(locations, byLoc, byFloor, 1650, 3829, 1, idx);
        idx = add(locations, byLoc, byFloor, 1652, 3831, 1, idx);
        idx = add(locations, byLoc, byFloor, 1653, 3831, 1, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3827, 1, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3826, 1, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3823, 1, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3822, 1, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3821, 1, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3820, 1, idx);
        idx = add(locations, byLoc, byFloor, 1656, 3816, 1, idx);
        idx = add(locations, byLoc, byFloor, 1655, 3816, 1, idx);
        idx = add(locations, byLoc, byFloor, 1651, 3816, 1, idx);
        idx = add(locations, byLoc, byFloor, 1649, 3816, 1, idx);
        idx = add(locations, byLoc, byFloor, 1648, 3816, 1, idx);
        idx = add(locations, byLoc, byFloor, 1644, 3816, 1, idx);
        idx = add(locations, byLoc, byFloor, 1643, 3816, 1, idx);

        // Top floor (plane 2) - includes remaining bookcases
        idx = add(locations, byLoc, byFloor, 1607, 3785, 2, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3786, 2, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3796, 2, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3797, 2, idx);
        idx = add(locations, byLoc, byFloor, 1608, 3799, 2, idx);
        idx = add(locations, byLoc, byFloor, 1610, 3799, 2, idx);
        idx = add(locations, byLoc, byFloor, 1611, 3799, 2, idx);
        idx = add(locations, byLoc, byFloor, 1618, 3799, 2, idx);
        idx = add(locations, byLoc, byFloor, 1621, 3799, 2, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3797, 2, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3795, 2, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3794, 2, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3792, 2, idx);
        idx = add(locations, byLoc, byFloor, 1623, 3791, 2, idx);
        idx = add(locations, byLoc, byFloor, 1622, 3791, 2, idx);
        idx = add(locations, byLoc, byFloor, 1618, 3792, 2, idx);
        idx = add(locations, byLoc, byFloor, 1618, 3793, 2, idx);
        idx = add(locations, byLoc, byFloor, 1618, 3794, 2, idx);
        idx = add(locations, byLoc, byFloor, 1617, 3793, 2, idx);
        idx = add(locations, byLoc, byFloor, 1617, 3792, 2, idx);
        idx = add(locations, byLoc, byFloor, 1618, 3790, 2, idx);
        idx = add(locations, byLoc, byFloor, 1620, 3790, 2, idx);
        idx = add(locations, byLoc, byFloor, 1622, 3790, 2, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3789, 2, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3788, 2, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3786, 2, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3785, 2, idx);
        idx = add(locations, byLoc, byFloor, 1623, 3784, 2, idx);
        idx = add(locations, byLoc, byFloor, 1621, 3784, 2, idx);
        idx = add(locations, byLoc, byFloor, 1611, 3784, 2, idx);
        idx = add(locations, byLoc, byFloor, 1609, 3784, 2, idx);
        // Continue with remaining floor 2 bookcases...
        idx = add(locations, byLoc, byFloor, 1611, 3816, 2, idx);
        idx = add(locations, byLoc, byFloor, 1610, 3816, 2, idx);
        idx = add(locations, byLoc, byFloor, 1609, 3816, 2, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3817, 2, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3819, 2, idx);
        idx = add(locations, byLoc, byFloor, 1607, 3829, 2, idx);
        idx = add(locations, byLoc, byFloor, 1608, 3831, 2, idx);
        idx = add(locations, byLoc, byFloor, 1610, 3831, 2, idx);
        idx = add(locations, byLoc, byFloor, 1611, 3831, 2, idx);
        idx = add(locations, byLoc, byFloor, 1622, 3831, 2, idx);
        idx = add(locations, byLoc, byFloor, 1623, 3831, 2, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3829, 2, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3828, 2, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3821, 2, idx);
        idx = add(locations, byLoc, byFloor, 1624, 3819, 2, idx);
        idx = add(locations, byLoc, byFloor, 1622, 3816, 2, idx);
        idx = add(locations, byLoc, byFloor, 1620, 3816, 2, idx);
        idx = add(locations, byLoc, byFloor, 1618, 3816, 2, idx);
        // East floor 2
        idx = add(locations, byLoc, byFloor, 1641, 3818, 2, idx);
        idx = add(locations, byLoc, byFloor, 1641, 3820, 2, idx);
        idx = add(locations, byLoc, byFloor, 1641, 3821, 2, idx);
        idx = add(locations, byLoc, byFloor, 1641, 3829, 2, idx);
        idx = add(locations, byLoc, byFloor, 1643, 3831, 2, idx);
        idx = add(locations, byLoc, byFloor, 1644, 3831, 2, idx);
        idx = add(locations, byLoc, byFloor, 1654, 3831, 2, idx);
        idx = add(locations, byLoc, byFloor, 1656, 3831, 2, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3830, 2, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3828, 2, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3818, 2, idx);
        idx = add(locations, byLoc, byFloor, 1658, 3817, 2, idx);
        idx = add(locations, byLoc, byFloor, 1656, 3816, 2, idx);
        idx = add(locations, byLoc, byFloor, 1655, 3816, 2, idx);
        idx = add(locations, byLoc, byFloor, 1652, 3816, 2, idx);
        idx = add(locations, byLoc, byFloor, 1648, 3817, 2, idx);
        idx = add(locations, byLoc, byFloor, 1648, 3819, 2, idx);
        idx = add(locations, byLoc, byFloor, 1648, 3821, 2, idx);
        idx = add(locations, byLoc, byFloor, 1645, 3816, 2, idx);
        idx = add(locations, byLoc, byFloor, 1644, 3816, 2, idx);
        // Center floor 2
        idx = add(locations, byLoc, byFloor, 1625, 3802, 2, idx);
        idx = add(locations, byLoc, byFloor, 1625, 3804, 2, idx);
        idx = add(locations, byLoc, byFloor, 1625, 3811, 2, idx);
        idx = add(locations, byLoc, byFloor, 1625, 3812, 2, idx);
        idx = add(locations, byLoc, byFloor, 1627, 3815, 2, idx);
        idx = add(locations, byLoc, byFloor, 1628, 3815, 2, idx);
        idx = add(locations, byLoc, byFloor, 1635, 3815, 2, idx);
        idx = add(locations, byLoc, byFloor, 1637, 3815, 2, idx);
        idx = add(locations, byLoc, byFloor, 1638, 3815, 2, idx);
        idx = add(locations, byLoc, byFloor, 1640, 3813, 2, idx);
        idx = add(locations, byLoc, byFloor, 1640, 3811, 2, idx);
        idx = add(locations, byLoc, byFloor, 1640, 3810, 2, idx);
        idx = add(locations, byLoc, byFloor, 1638, 3800, 2, idx);
        idx = add(locations, byLoc, byFloor, 1632, 3800, 2, idx);
        idx = add(locations, byLoc, byFloor, 1630, 3800, 2, idx);
        idx = add(locations, byLoc, byFloor, 1629, 3800, 2, idx);
        idx = add(locations, byLoc, byFloor, 1627, 3800, 2, idx);

        ALL_BOOKCASES = Collections.unmodifiableList(locations);
        BY_LOCATION = Collections.unmodifiableMap(byLoc);
        BY_FLOOR = Collections.unmodifiableMap(byFloor);
    }

    private static int add(List<WorldPoint> locations, Map<WorldPoint, LibraryBookcase> byLoc,
                           Map<Integer, List<LibraryBookcase>> byFloor, int x, int y, int z, int idx) {
        WorldPoint point = new WorldPoint(x, y, z);
        LibraryBookcase bc = new LibraryBookcase(point, idx);
        locations.add(point);
        byLoc.put(point, bc);
        byFloor.computeIfAbsent(z, k -> new ArrayList<>()).add(bc);
        return idx + 1;
    }
}

