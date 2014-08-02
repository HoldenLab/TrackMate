package fiji.plugin.trackmate.tracking.sparselap;

import static fiji.plugin.trackmate.tracking.LAPUtils.checkFeatureMap;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_ALTERNATIVE_LINKING_COST_FACTOR;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_CUTOFF_PERCENTILE;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_LINKING_FEATURE_PENALTIES;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_LINKING_MAX_DISTANCE;
import static fiji.plugin.trackmate.util.TMUtils.checkMapKeys;
import static fiji.plugin.trackmate.util.TMUtils.checkParameter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.imglib2.algorithm.MultiThreadedBenchmarkAlgorithm;
import net.imglib2.multithreading.SimpleMultiThreading;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.tracking.SpotTracker;
import fiji.plugin.trackmate.tracking.sparselap.linker.CostFunction;
import fiji.plugin.trackmate.tracking.sparselap.linker.SparseJaqamanLinker;

class SparseLAPFrameToFrameTracker extends MultiThreadedBenchmarkAlgorithm implements SpotTracker
{
	private final static String BASE_ERROR_MESSAGE = "[SparseLAPFrameToFrameTracker] ";

	private SimpleWeightedGraph< Spot, DefaultWeightedEdge > graph;

	private Logger logger;

	private final SpotCollection spots;

	private final Map< String, Object > settings;

	/*
	 * CONSTRUCTOR
	 */

	SparseLAPFrameToFrameTracker( final SpotCollection spots, final Map< String, Object > settings )
	{
		this.spots = spots;
		this.settings = settings;
	}

	/*
	 * METHODS
	 */

	@Override
	public SimpleWeightedGraph< Spot, DefaultWeightedEdge > getResult()
	{
		return graph;
	}

	@Override
	public boolean checkInput()
	{
		return true;
	}

	@Override
	public boolean process()
	{
		/*
		 * Check input now.
		 */

		// Check that the objects list itself isn't null
		if ( null == spots )
		{
			errorMessage = BASE_ERROR_MESSAGE + "The spot collection is null.";
			return false;
		}

		// Check that the objects list contains inner collections.
		if ( spots.keySet().isEmpty() )
		{
			errorMessage = BASE_ERROR_MESSAGE + "The spot collection is empty.";
			return false;
		}

		// Check that at least one inner collection contains an object.
		boolean empty = true;
		for ( final int frame : spots.keySet() )
		{
			if ( spots.getNSpots( frame, true ) > 0 )
			{
				empty = false;
				break;
			}
		}
		if ( empty )
		{
			errorMessage = BASE_ERROR_MESSAGE + "The spot collection is empty.";
			return false;
		}
		// Check parameters
		final StringBuilder errorHolder = new StringBuilder();
		if ( !checkSettingsValidity( settings, errorHolder ) )
		{
			errorMessage = errorHolder.toString();
			return false;
		}

		/*
		 * Process.
		 */

		final long start = System.currentTimeMillis();

		// Prepare frame pairs in order, not necessarily separated by 1.
		final ArrayList< int[] > framePairs = new ArrayList< int[] >( spots.keySet().size() - 1 );
		final Iterator< Integer > frameIterator = spots.keySet().iterator();
		int frame0 = frameIterator.next();
		int frame1;
		while ( frameIterator.hasNext() )
		{ // ascending order
			frame1 = frameIterator.next();
			framePairs.add( new int[] { frame0, frame1 } );
			frame0 = frame1;
		}

		// Prepare cost function
		@SuppressWarnings( "unchecked" )
		final Map< String, Double > featurePenalties = ( Map< String, Double > ) settings.get( KEY_LINKING_FEATURE_PENALTIES );
		final CostFunction< Spot > costFunction;
		if ( null == featurePenalties || featurePenalties.isEmpty() )
		{
			costFunction = new DefaultCostFunction();
		}
		else
		{
			costFunction = new FeaturePenaltyCostFunction( featurePenalties );
		}
		final Double maxDist = ( Double ) settings.get( KEY_LINKING_MAX_DISTANCE );
		final double costThreshold = maxDist * maxDist;
		final double alternativeCostFactor = ( Double ) settings.get( KEY_ALTERNATIVE_LINKING_COST_FACTOR );

		// Instantiate graph
		graph = new SimpleWeightedGraph< Spot, DefaultWeightedEdge >( DefaultWeightedEdge.class );

		// Prepare threads
		final Thread[] threads = SimpleMultiThreading.newThreads( numThreads );

		// Prepare the thread array
		final AtomicInteger ai = new AtomicInteger( 0 );
		final AtomicInteger progress = new AtomicInteger( 0 );
		final AtomicBoolean ok = new AtomicBoolean( true );
		for ( int ithread = 0; ithread < threads.length; ithread++ )
		{
			threads[ ithread ] = new Thread( BASE_ERROR_MESSAGE + " thread " + ( 1 + ithread ) + "/" + threads.length )
			{
				@Override
				public void run()
				{
					for ( int i = ai.getAndIncrement(); i < framePairs.size(); i = ai.getAndIncrement() )
					{
						if ( !ok.get() )
						{
							break;
						}

						// Get frame pairs
						final int frame0 = framePairs.get( i )[ 0 ];
						final int frame1 = framePairs.get( i )[ 1 ];

						// Get spots - we have to create a list from each
						// content
						final List< Spot > sources = new ArrayList< Spot >( spots.getNSpots( frame0, true ) );
						for ( final Iterator< Spot > iterator = spots.iterator( frame0, true ); iterator.hasNext(); )
						{
							sources.add( iterator.next() );
						}

						final List< Spot > targets = new ArrayList< Spot >( spots.getNSpots( frame1, true ) );
						for ( final Iterator< Spot > iterator = spots.iterator( frame1, true ); iterator.hasNext(); )
						{
							targets.add( iterator.next() );
						}

						/*
						 * Run the linker.
						 */

						final SparseJaqamanLinker< Spot > linker = new SparseJaqamanLinker< Spot >( sources, targets, costFunction, costThreshold, alternativeCostFactor );
						if ( !linker.checkInput() || !linker.process() )
						{
							errorMessage = linker.getErrorMessage();
							ok.set( false );
							return;
						}

						/*
						 * Update graph.
						 */

						synchronized ( graph )
						{
							final double[] costs = linker.getAssignmentCosts();
							final int[] assignment = linker.getResult();
							for ( int s = 0; s < assignment.length; s++ )
							{
								final int t = assignment[ s ];
								if ( t < targets.size() && s < sources.size() )
								{
									final Spot source = sources.get( s );
									final Spot target = targets.get( t );
									graph.addVertex( source );
									graph.addVertex( target );
									final double cost = costs[ s ];
									final DefaultWeightedEdge edge = graph.addEdge( source, target );
									graph.setEdgeWeight( edge, cost );
								}
							}
						}

						logger.setProgress( 0.5f * progress.incrementAndGet() / framePairs.size() );

					}
				}
			};
		}

		logger.setStatus( "Solving for track segments..." );
		SimpleMultiThreading.startAndJoin( threads );
		logger.setProgress( 0.5f );
		logger.setStatus( "" );

		final long end = System.currentTimeMillis();
		processingTime = end - start;

		return ok.get();
	}

	@Override
	public void setLogger( final Logger logger )
	{
		this.logger = logger;
	}

	private static final boolean checkSettingsValidity( final Map< String, Object > settings, final StringBuilder str )
	{
		if ( null == settings )
		{
			str.append( "Settings map is null.\n" );
			return false;
		}

		boolean ok = true;
		// Linking
		ok = ok & checkParameter( settings, KEY_LINKING_MAX_DISTANCE, Double.class, str );
		ok = ok & checkFeatureMap( settings, KEY_LINKING_FEATURE_PENALTIES, str );
		// Others
		ok = ok & checkParameter( settings, KEY_CUTOFF_PERCENTILE, Double.class, str );
		ok = ok & checkParameter( settings, KEY_ALTERNATIVE_LINKING_COST_FACTOR, Double.class, str );

		// Check keys
		final List< String > mandatoryKeys = new ArrayList< String >();
		mandatoryKeys.add( KEY_LINKING_MAX_DISTANCE );
		mandatoryKeys.add( KEY_ALTERNATIVE_LINKING_COST_FACTOR );
		mandatoryKeys.add( KEY_CUTOFF_PERCENTILE );
		final List< String > optionalKeys = new ArrayList< String >();
		optionalKeys.add( KEY_LINKING_FEATURE_PENALTIES );
		ok = ok & checkMapKeys( settings, mandatoryKeys, optionalKeys, str );

		return ok;
	}

}
