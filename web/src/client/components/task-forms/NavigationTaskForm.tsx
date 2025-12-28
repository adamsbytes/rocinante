import { type Component, createSignal, createResource, For, Show, createMemo } from 'solid-js';
import type { NavigationTaskSpec, LocationInfo } from '../../../shared/types';

interface NavigationTaskFormProps {
  onSubmit: (task: NavigationTaskSpec) => void;
  submitting?: boolean;
}

const LOCATION_TYPES = [
  { value: '', label: 'All Types' },
  { value: 'BANK', label: '🏦 Banks' },
  { value: 'SHOP', label: '🏪 Shops' },
  { value: 'TRAINING', label: '⚔️ Training Spots' },
  { value: 'QUEST', label: '📜 Quest Locations' },
  { value: 'TRANSPORT', label: '🚀 Transport' },
  { value: 'GENERIC', label: '📍 Other' },
] as const;

/**
 * Fetch locations from API.
 */
async function fetchLocations(): Promise<LocationInfo[]> {
  const response = await fetch('/api/data/locations');
  if (!response.ok) throw new Error('Failed to load locations');
  const data = await response.json();
  return data.data;
}

/**
 * Navigation Task Form - create walk-to tasks.
 * 
 * Features:
 * - Location dropdown with search/filter
 * - Filter by location type
 * - Manual coordinate input option
 * - Shows location coordinates
 */
export const NavigationTaskForm: Component<NavigationTaskFormProps> = (props) => {
  const [locations] = createResource(fetchLocations);
  
  const [inputMode, setInputMode] = createSignal<'location' | 'coordinates'>('location');
  const [typeFilter, setTypeFilter] = createSignal<string>('');
  const [searchQuery, setSearchQuery] = createSignal('');
  const [selectedLocation, setSelectedLocation] = createSignal<string>('');
  const [manualX, setManualX] = createSignal(0);
  const [manualY, setManualY] = createSignal(0);
  const [manualPlane, setManualPlane] = createSignal(0);
  const [randomRadius, setRandomRadius] = createSignal(0);

  // Filter locations by type and search query
  const filteredLocations = createMemo(() => {
    if (!locations()) return [];
    
    let result = locations()!;
    
    // Filter by type
    const type = typeFilter();
    if (type) {
      result = result.filter(l => l.type === type);
    }
    
    // Filter by search query
    const query = searchQuery().toLowerCase();
    if (query) {
      result = result.filter(l => 
        l.name.toLowerCase().includes(query) ||
        l.id.toLowerCase().includes(query) ||
        l.tags.some(t => t.toLowerCase().includes(query))
      );
    }
    
    // Sort by name
    return result.sort((a, b) => a.name.localeCompare(b.name));
  });

  // Get selected location object
  const selectedLocationObj = createMemo(() => {
    const id = selectedLocation();
    if (!id || !locations()) return null;
    return locations()!.find(l => l.id === id) || null;
  });

  // Handle form submission
  const handleSubmit = (e: Event) => {
    e.preventDefault();
    
    const task: NavigationTaskSpec = {
      taskType: 'NAVIGATION',
    };

    if (inputMode() === 'location') {
      const loc = selectedLocationObj();
      if (!loc) return;
      task.locationId = loc.id;
      task.description = `Walk to ${loc.name}`;
    } else {
      if (!manualX() && !manualY()) return;
      task.x = manualX();
      task.y = manualY();
      task.plane = manualPlane();
      task.description = `Walk to (${manualX()}, ${manualY()})`;
    }

    if (randomRadius() > 0) {
      task.randomRadius = randomRadius();
    }

    props.onSubmit(task);
  };

  // Validate form
  const isValid = () => {
    if (inputMode() === 'location') {
      return !!selectedLocation();
    } else {
      return manualX() !== 0 || manualY() !== 0;
    }
  };

  return (
    <form onSubmit={handleSubmit} class="space-y-4">
      {/* Input Mode Toggle */}
      <div class="flex gap-2 p-1 bg-gray-700 rounded-lg">
        <button
          type="button"
          onClick={() => setInputMode('location')}
          class={`flex-1 px-3 py-2 rounded-md text-sm font-medium transition-colors ${
            inputMode() === 'location'
              ? 'bg-amber-600 text-white'
              : 'text-gray-400 hover:text-white'
          }`}
        >
          📍 Named Location
        </button>
        <button
          type="button"
          onClick={() => setInputMode('coordinates')}
          class={`flex-1 px-3 py-2 rounded-md text-sm font-medium transition-colors ${
            inputMode() === 'coordinates'
              ? 'bg-amber-600 text-white'
              : 'text-gray-400 hover:text-white'
          }`}
        >
          🗺️ Coordinates
        </button>
      </div>

      {/* Location Selection */}
      <Show when={inputMode() === 'location'}>
        {/* Filters */}
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-sm font-medium text-gray-300 mb-1">
              Type
            </label>
            <select
              value={typeFilter()}
              onChange={(e) => setTypeFilter(e.target.value)}
              class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
            >
              <For each={LOCATION_TYPES}>
                {(type) => (
                  <option value={type.value}>{type.label}</option>
                )}
              </For>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-300 mb-1">
              Search
            </label>
            <input
              type="text"
              value={searchQuery()}
              onInput={(e) => setSearchQuery(e.target.value)}
              placeholder="Search locations..."
              class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-amber-500"
            />
          </div>
        </div>

        {/* Location Dropdown */}
        <div>
          <label class="block text-sm font-medium text-gray-300 mb-1">
            Location
          </label>
          <Show
            when={!locations.loading}
            fallback={<div class="text-gray-400 text-sm">Loading locations...</div>}
          >
            <select
              value={selectedLocation()}
              onChange={(e) => setSelectedLocation(e.target.value)}
              class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
              size={8}
            >
              <option value="">Select a location...</option>
              <For each={filteredLocations()}>
                {(loc) => (
                  <option value={loc.id}>
                    {loc.name} ({loc.type.toLowerCase()})
                  </option>
                )}
              </For>
            </select>
          </Show>
          <p class="text-xs text-gray-500 mt-1">
            {filteredLocations().length} locations available
          </p>
        </div>

        {/* Selected Location Info */}
        <Show when={selectedLocationObj()}>
          {(loc) => (
            <div class="bg-gray-700/50 rounded-lg p-3 space-y-2">
              <div class="flex justify-between text-sm">
                <span class="text-gray-400">Coordinates:</span>
                <span class="text-gray-200 font-mono">
                  ({loc().x}, {loc().y}, {loc().plane})
                </span>
              </div>
              <Show when={loc().tags.length > 0}>
                <div class="flex flex-wrap gap-1">
                  <For each={loc().tags}>
                    {(tag) => (
                      <span class="px-2 py-0.5 text-xs bg-gray-600 text-gray-300 rounded">
                        {tag}
                      </span>
                    )}
                  </For>
                </div>
              </Show>
            </div>
          )}
        </Show>
      </Show>

      {/* Manual Coordinates Input */}
      <Show when={inputMode() === 'coordinates'}>
        <div class="grid grid-cols-3 gap-3">
          <div>
            <label class="block text-sm font-medium text-gray-300 mb-1">X</label>
            <input
              type="number"
              value={manualX()}
              onInput={(e) => setManualX(parseInt(e.target.value) || 0)}
              class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
            />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-300 mb-1">Y</label>
            <input
              type="number"
              value={manualY()}
              onInput={(e) => setManualY(parseInt(e.target.value) || 0)}
              class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
            />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-300 mb-1">Plane</label>
            <input
              type="number"
              value={manualPlane()}
              onInput={(e) => setManualPlane(parseInt(e.target.value) || 0)}
              min={0}
              max={3}
              class="w-full bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
            />
          </div>
        </div>
        <p class="text-xs text-gray-500">
          Tip: You can find coordinates in RuneLite by right-clicking the world map
        </p>
      </Show>

      {/* Random Radius */}
      <div>
        <label class="block text-sm font-medium text-gray-300 mb-1">
          Random Offset Radius (tiles)
        </label>
        <input
          type="number"
          value={randomRadius()}
          onInput={(e) => setRandomRadius(parseInt(e.target.value) || 0)}
          min={0}
          max={10}
          class="w-32 bg-gray-700 border border-gray-600 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-amber-500"
        />
        <p class="text-xs text-gray-500 mt-1">
          Adds human-like variation to destination
        </p>
      </div>

      {/* Submit Button */}
      <button
        type="submit"
        disabled={!isValid() || props.submitting}
        class="w-full px-4 py-2.5 bg-amber-600 hover:bg-amber-700 disabled:bg-gray-600 disabled:cursor-not-allowed text-white font-medium rounded-lg transition-colors"
      >
        {props.submitting ? 'Queuing...' : 'Queue Navigation Task'}
      </button>
    </form>
  );
};

export default NavigationTaskForm;

