package fiji.plugin.trackmate.visualization;

import java.awt.Color;

import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.features.manual.ManualSpotColorAnalyzerFactory;
import fiji.plugin.trackmate.gui.panels.components.ColorByFeatureGUIPanel;

public class ManualSpotColorGenerator implements FeatureColorGenerator< Spot >
{
	@Override
	public Color color( final Spot spot )
	{
		final Double val = spot.getFeature( ManualSpotColorAnalyzerFactory.FEATURE );
		if ( null == val ) { return TrackMateModelView.DEFAULT_UNASSIGNED_FEATURE_COLOR; }
		return new Color( val.intValue() );
	}

	@Override
	public void setFeature( final String feature )
	{}

	@Override
	public String getFeature()
	{
		return ColorByFeatureGUIPanel.MANUAL_KEY;
	}

	@Override
	public void terminate()
	{}

	@Override
	public void activate()
	{}
}