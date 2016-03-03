package org.matsim.contrib.gtfs;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.conveyal.gtfs.model.*;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.misc.Time;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import com.conveyal.gtfs.GTFSFeed;

public class GtfsConverter {
    
	private GTFSFeed feed;
	private CoordinateTransformation transform;
	private MutableScenario scenario;
	private TransitSchedule ts;
	private LocalDate date = LocalDate.now();

	public GtfsConverter(GTFSFeed feed, Scenario scenario, CoordinateTransformation transform) {
		this.feed = feed;
		this.transform = transform;
		this.scenario = (MutableScenario) scenario;
	}

	public void setDate(LocalDate date) {
		this.date = date;
	}

	public void convert(){

		this.ts = scenario.getTransitSchedule();

		// Put all stops in the Schedule
		this.convertStops();

		int startDate = Integer.MAX_VALUE;
		for(Service service: this.feed.services.values()) {
		    if(service.calendar !=null && service.calendar.start_date<startDate) {
			startDate = service.calendar.start_date;
		    }
		}
		
		int endDate = Integer.MIN_VALUE;
		for(Service service: this.feed.services.values()) {
		    if(service.calendar !=null && service.calendar.end_date>endDate) {
			endDate = service.calendar.end_date;
		    }
		}
		
//		this.feed.services.values().stream().flatMapToInt(service -> service.calendar)).min(Integer::compareTo).ifPresent(startDate -> {
			System.out.println("Earliest service date: "+startDate);
//		});
//		this.feed.services.values().stream().flatMap(service -> service.calendar_dates.keySet().stream()).max(LocalDate::compareTo).ifPresent(endDate -> {
			System.out.println("Latest service date: " + endDate);
//		});

		// Get the used service Id for the chosen weekday and date
		List<String> activeServiceIds = this.getActiveServiceIds(this.feed.services);
		System.out.printf("Active Services: %d %s\n", activeServiceIds.size(), activeServiceIds);

		// Get the Trips which are active today
		List<Trip> activeTrips = feed.trips.values().stream().filter(trip -> activeOn(trip.service)/*trip.service.activeOn(this.date)*/ ).collect(Collectors.toList());
		System.out.printf("Active Trips: %d %s\n", activeTrips.size(), activeTrips.stream().map(trip -> trip.trip_id).collect(Collectors.toList()));

		// Create one TransitLine for each GTFS-Route which has an active trip
		activeTrips.stream().map(trip -> trip.route).distinct().forEach(route -> {
			TransitLine tl = ts.getFactory().createTransitLine(Id.create(route.route_id, TransitLine.class));
			ts.addTransitLine(tl);
		});

		this.convertTrips(activeTrips);

		if(activeTrips.isEmpty()){
			System.out.println("There are no converted trips. You might need to change the date for better results.");
		}
		System.out.println("Conversion successfull");
	}
	
	
	private void convertStops(){
		for(Stop stop: feed.stops.values()){
			TransitStopFacility t = this.ts.getFactory().createTransitStopFacility(Id.create(stop.stop_id, TransitStopFacility.class), transform.transform(new Coord(stop.stop_lon, stop.stop_lat)), false);
			t.setName(stop.stop_name);
			ts.addStopFacility(t);
		}		
	}


	private List<String> getActiveServiceIds(Map<String, Service> services) {
		List<String> serviceIds = new ArrayList<>();
		System.out.println("Used Date for active schedules: " + this.date.toString() + " (weekday: " + date.getDayOfWeek().toString() + "). If you want to choose another date, please specify it, before running the converter");
		for(Service service: services.values()){
			if(/*service.activeOn(date)*/ activeOn(service)){
				serviceIds.add(service.service_id);
			}
		}
		return serviceIds;
	}
	
	
	//fix for conveyal-lib as long as it does not regard day of week
	private boolean activeOn(Service service) {
		
		Calendar calendar = service.calendar;
		CalendarDate exception = service.calendar_dates.get(date);

		if (exception != null)
			return exception.exception_type == 1;

		else if (calendar == null)
			return false;

		else {
			int gtfsDate = date.getYear() * 10000 + date.getMonthValue() * 100 + date.getDayOfMonth();
			if( calendar.end_date >= gtfsDate && calendar.start_date <= gtfsDate) {
				switch(date.getDayOfWeek().getValue()) {
				case 1: return calendar.monday==1;
				case 2: return calendar.tuesday==1;
				case 3: return calendar.wednesday==1;
				case 4: return calendar.thursday==1;
				case 5: return calendar.friday==1;
				case 6: return calendar.saturday==1;
				case 7: return calendar.sunday==1;
				}
			}
			return false;
		}
	}


	private void convertTrips(List<Trip> trips) {
		int scheduleDepartures = 0;
		int frequencyDepartures = 0;
		for (Trip trip : trips) {
			if (trip.frequencies == null) {
				StopTime firstStopTime = feed.getOrderedStopTimesForTrip(trip.trip_id).iterator().next();
				Double departureTime = Time.parseTime(String.valueOf(firstStopTime.departure_time));
				List<TransitRouteStop> stops = new ArrayList<>();
				for(StopTime stopTime : feed.getOrderedStopTimesForTrip(trip.trip_id)) {
					Id<TransitStopFacility> stopId = Id.create(stopTime.stop_id, TransitStopFacility.class);
					TransitStopFacility stop = ts.getFacilities().get(stopId);
					TransitRouteStop routeStop = ts.getFactory().createTransitRouteStop(stop, Time.parseTime(String.valueOf(stopTime.arrival_time))-departureTime, Time.parseTime(String.valueOf(stopTime.departure_time))-departureTime);
					stops.add(routeStop);
				}
				TransitLine tl = ts.getTransitLines().get(Id.create(trip.route.route_id, TransitLine.class));
				TransitRoute tr = findOrAddTransitRoute(tl, trip.route, stops);
				Departure departure = ts.getFactory().createDeparture(Id.create(trip.trip_id, Departure.class), departureTime);
				tr.addDeparture(departure);
				scheduleDepartures++;
			} else {
				List<TransitRouteStop> stops = new ArrayList<>();
				for(StopTime stopTime : feed.getOrderedStopTimesForTrip(trip.trip_id)) {
					Id<TransitStopFacility> stopId = Id.create(stopTime.stop_id, TransitStopFacility.class);
					TransitStopFacility stop = ts.getFacilities().get(stopId);
					TransitRouteStop routeStop = ts.getFactory().createTransitRouteStop(stop, Time.parseTime(String.valueOf(stopTime.arrival_time)), Time.parseTime(String.valueOf(stopTime.departure_time)));
					stops.add(routeStop);
				}
				for (Frequency frequency : trip.frequencies) {
					for (int time = frequency.start_time; time < frequency.end_time; time += frequency.headway_secs) {
						TransitLine tl = ts.getTransitLines().get(Id.create(trip.route.route_id, TransitLine.class));
						TransitRoute tr = findOrAddTransitRoute(tl, trip.route, stops);
						Departure d = ts.getFactory().createDeparture(Id.create(trip.trip_id + "." + time, Departure.class), time);
						tr.addDeparture(d);
						frequencyDepartures++;
					}
				}
			}
		}
		System.out.println("Created schedule-based departures: " + scheduleDepartures);
		System.out.println("Created frequency-based departures: " + frequencyDepartures);
	}


	private TransitRoute findOrAddTransitRoute(TransitLine tl, Route route, List<TransitRouteStop> stops) {
		for (TransitRoute tr : tl.getRoutes().values()) {
			if (tr.getStops().equals(stops)) {
				return tr;
			} 
		}
		Id<TransitRoute> routeId = Id.create(tl.getId().toString() + "_" + tl.getRoutes().size(), TransitRoute.class);

		TransitRoute tr = ts.getFactory().createTransitRoute(routeId, /*networkRoute*/ null, stops, RouteType.values()[route.route_type].toString());
		tl.addRoute(tr);
		return tr;
	}
	
}
