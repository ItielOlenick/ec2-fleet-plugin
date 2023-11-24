package com.amazon.jenkins.ec2fleet;

import com.amazon.jenkins.ec2fleet.fleet.EC2Fleet;
import com.amazon.jenkins.ec2fleet.fleet.EC2Fleets;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

@RunWith(PowerMockRunner.class)
@PrepareForTest({CloudNanny.class, Jenkins.class, EC2Fleets.class})
public class CloudNannyTest {
    @Mock
    private Jenkins jenkins;

    @Mock
    private EC2Fleet ec2Fleet;

    @Mock
    private EC2FleetCloud cloud1;

    @Mock
    private EC2FleetCloud cloud2;

    private Jenkins.CloudList clouds = new Jenkins.CloudList();

    private FleetStateStats stats1 = new FleetStateStats(
            "f1", 1, new FleetStateStats.State(true, false, "a"), Collections.emptySet(), Collections.<String, Double>emptyMap());

    private FleetStateStats stats2 = new FleetStateStats(
            "f2", 1, new FleetStateStats.State(true, false, "a"), Collections.emptySet(), Collections.<String, Double>emptyMap());

    private int recurrencePeriod = 45;

    private AtomicInteger recurrenceCounter1 = new AtomicInteger();
    private AtomicInteger recurrenceCounter2 = new AtomicInteger();

    private Map<EC2FleetCloud, AtomicInteger> recurrenceCounters = Collections.synchronizedMap(new WeakHashMap<>());

    @Before
    public void before() throws Exception {
        PowerMockito.mockStatic(CloudNanny.class);
        PowerMockito.when(CloudNanny.class, "getClouds").thenReturn(clouds);

        PowerMockito.mockStatic(Jenkins.class);
        PowerMockito.when(Jenkins.get()).thenReturn(jenkins);

        PowerMockito.mockStatic(EC2Fleets.class);
        when(EC2Fleets.get(anyString())).thenReturn(ec2Fleet);
        PowerMockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        when(cloud1.getLabelString()).thenReturn("a");
        when(cloud2.getLabelString()).thenReturn("");
        when(cloud1.getFleet()).thenReturn("f1");
        when(cloud2.getFleet()).thenReturn("f2");

        when(cloud1.update()).thenReturn(stats1);
        when(cloud2.update()).thenReturn(stats2);

        when(cloud1.getCloudStatusIntervalSec()).thenReturn(recurrencePeriod);
        when(cloud2.getCloudStatusIntervalSec()).thenReturn(recurrencePeriod * 2);

        recurrenceCounters.put(cloud1, recurrenceCounter1);
        recurrenceCounters.put(cloud2, recurrenceCounter2);
    }

    private CloudNanny getMockCloudNannyInstance() {
        CloudNanny cloudNanny = Whitebox.newInstance(CloudNanny.class);

        // next execution should trigger running the status check.
        recurrenceCounter1.set(1);
        recurrenceCounter2.set(1);

        Whitebox.setInternalState(cloudNanny, "recurrenceCounters", recurrenceCounters);

        return cloudNanny;
    }

    @Test
    public void shouldDoNothingIfNoCloudsAndWidgets() {
        getMockCloudNannyInstance().doRun();
    }

    @Test
    public void shouldUpdateCloudAndDoNothingIfNoWidgets() {
        clouds.add(cloud1);
        clouds.add(cloud2);

        getMockCloudNannyInstance().doRun();
    }

    @Test
    public void shouldIgnoreNonEC2FleetClouds() {
        clouds.add(cloud1);

        Cloud nonEc2FleetCloud = mock(Cloud.class);
        clouds.add(nonEc2FleetCloud);

        getMockCloudNannyInstance().doRun();

        verify(cloud1).update();
        verifyZeroInteractions(nonEc2FleetCloud);
    }

    @Test
    public void shouldUpdateCloudCollectAll() {
        clouds.add(cloud1);
        clouds.add(cloud2);

        getMockCloudNannyInstance().doRun();

        verify(cloud1).update();
        verify(cloud2).update();
    }

    @Test
    public void shouldIgnoreExceptionsFromUpdateForOneofCloudAndUpdateOther() {
        clouds.add(cloud1);
        clouds.add(cloud2);

        when(cloud1.update()).thenThrow(new IllegalArgumentException("test"));

        getMockCloudNannyInstance().doRun();

        verify(cloud1).update();
        verify(cloud2).update();
    }

    @Test
    public void resetCloudInterval() {
        clouds.add(cloud1);
        clouds.add(cloud2);
        CloudNanny cloudNanny = getMockCloudNannyInstance();

        cloudNanny.doRun();

        verify(cloud1).update();
        verify(cloud1, atLeastOnce()).getCloudStatusIntervalSec();
        verify(cloud2).update();
        verify(cloud2, atLeastOnce()).getCloudStatusIntervalSec();


        assertEquals(cloud1.getCloudStatusIntervalSec(), recurrenceCounter1.get());
        assertEquals(cloud2.getCloudStatusIntervalSec(), recurrenceCounter2.get());
    }

    @Test
    public void skipCloudIntervalExecution() {
        clouds.add(cloud1);
        clouds.add(cloud2);
        CloudNanny cloudNanny = getMockCloudNannyInstance();
        recurrenceCounter1.set(2);
        recurrenceCounter2.set(3);

        cloudNanny.doRun();

        verify(cloud1, atLeastOnce()).getCloudStatusIntervalSec();
        verify(cloud2, atLeastOnce()).getCloudStatusIntervalSec();
        verifyNoMoreInteractions(cloud1, cloud2);

        assertEquals(1, recurrenceCounter1.get());
        assertEquals(2, recurrenceCounter2.get());
    }

    @Test
    public void updateOnlyOneCloud() {
        clouds.add(cloud1);
        clouds.add(cloud2);
        CloudNanny cloudNanny = getMockCloudNannyInstance();
        recurrenceCounter1.set(2);
        recurrenceCounter2.set(1);

        cloudNanny.doRun();

        verify(cloud2, atLeastOnce()).getCloudStatusIntervalSec();
        verify(cloud2).update();

        verify(cloud1, atLeastOnce()).getCloudStatusIntervalSec();
        verifyNoMoreInteractions(cloud1);

        assertEquals(1, recurrenceCounter1.get());
        assertEquals(cloud2.getCloudStatusIntervalSec(), recurrenceCounter2.get());
    }

    @Test
    public void doRun_updatesCloudsWithScaler_whenScalerIsNull() {
        when(cloud1.isScaleExecutorsByWeight()).thenReturn(true);
        when(cloud2.isScaleExecutorsByWeight()).thenReturn(false);

        clouds.add(cloud1);
        clouds.add(cloud2);
        CloudNanny cloudNanny = getMockCloudNannyInstance();

        cloudNanny.doRun();

        cloud1 = (EC2FleetCloud) clouds.get(0);
        cloud2 = (EC2FleetCloud) clouds.get(1);

        assertEquals(EC2FleetCloud.WeightedScaler.class, cloud1.getExecutorScaler().getClass());
        assertEquals(EC2FleetCloud.NoScaler.class, cloud2.getExecutorScaler().getClass());
    }
}
