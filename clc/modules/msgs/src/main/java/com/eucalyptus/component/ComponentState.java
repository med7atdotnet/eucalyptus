/*******************************************************************************
 * Copyright (c) 2009  Eucalyptus Systems, Inc.
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, only version 3 of the License.
 * 
 * 
 *  This file is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 * 
 *  You should have received a copy of the GNU General Public License along
 *  with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 *  Please contact Eucalyptus Systems, Inc., 130 Castilian
 *  Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
 *  if you need additional information or have any questions.
 * 
 *  This file may incorporate work covered under the following copyright and
 *  permission notice:
 * 
 *    Software License Agreement (BSD License)
 * 
 *    Copyright (c) 2008, Regents of the University of California
 *    All rights reserved.
 * 
 *    Redistribution and use of this software in source and binary forms, with
 *    or without modification, are permitted provided that the following
 *    conditions are met:
 * 
 *      Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 * 
 *      Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 * 
 *    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 *    IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 *    TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 *    PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 *    OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 *    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *    PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 *    PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *    LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *    NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *    SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
 *    THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
 *    LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
 *    SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *    IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
 *    BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
 *    THE REGENTS DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
 *    OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
 *    WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
 *    ANY SUCH LICENSES OR RIGHTS.
 *******************************************************************************
 * @author chris grzegorczyk <grze@eucalyptus.com>
 */

package com.eucalyptus.component;

import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentSkipListSet;
import javax.persistence.Transient;
import org.apache.log4j.Logger;
import com.eucalyptus.bootstrap.Bootstrap;
import com.eucalyptus.component.Component.State;
import com.eucalyptus.component.Component.Transition;
import com.eucalyptus.empyrean.Empyrean;
import com.eucalyptus.system.Threads;
import com.eucalyptus.util.Exceptions;
import com.eucalyptus.util.async.Callback;
import com.eucalyptus.util.async.CheckedListenableFuture;
import com.eucalyptus.util.async.Callback.Completion;
import com.eucalyptus.util.async.Futures;
import com.eucalyptus.util.fsm.AtomicMarkedState;
import com.eucalyptus.util.fsm.AtomicMarkedState.ActiveTransition;
import com.eucalyptus.util.fsm.ExistingTransitionException;
import com.eucalyptus.util.fsm.TransitionAction;
import com.eucalyptus.util.fsm.StateMachineBuilder;
import com.eucalyptus.util.fsm.TransitionListener;
import com.eucalyptus.util.fsm.Transitions;
import com.eucalyptus.ws.util.PipelineRegistry;
import com.google.common.base.Predicate;

public class ComponentState {
  private static Logger                                         LOG     = Logger.getLogger( ComponentState.class );
  private final AtomicMarkedState<Component, State, Transition> stateMachine;
  private final Component                                       parent;
  private Component.State                                       goal    = Component.State.ENABLED;
  private final NavigableSet<String>                            details = new ConcurrentSkipListSet<String>( );
  
  public ComponentState( Component parent ) {
    this.parent = parent;
    this.stateMachine = this.buildStateMachine( );
  }
  
  public State getState( ) {
    return this.stateMachine.getState( );
  }
  
  public String getDetails( ) {
    return this.details.toString( );
  }
  
  private AtomicMarkedState<Component, State, Transition> buildStateMachine( ) {
    final TransitionAction<Component> loadTransition = new TransitionAction<Component>( ) {
      
      @Override
      public void leave( Component parent, Completion transitionCallback ) {
        ComponentState.this.details.clear( );
        try {
          parent.getBootstrapper( ).load( );
          transitionCallback.fire( );
        } catch ( Throwable ex ) {
          LOG.error( "Transition failed on " + parent.getName( ) + " due to " + ex.toString( ), ex );
          ComponentState.this.details.add( ex.toString( ) );
          transitionCallback.fireException( ex );
        }
      }
    };
    
    final TransitionAction<Component> startTransition = new TransitionAction<Component>( ) {
      @Override
      public void leave( final Component parent, final Completion transitionCallback ) {
        try {
          parent.getBootstrapper( ).start( );
          if ( parent.getBuilder( ) != null && parent.getLocalService( ) != null ) {
            parent.getBuilder( ).fireStart( parent.getLocalService( ).getServiceConfiguration( ) );
          }
          transitionCallback.fire( );
        } catch ( Throwable ex ) {
          LOG.error( "Transition failed on " + parent.getName( ) + " due to " + ex.toString( ), ex );
          ComponentState.this.details.add( ex.toString( ) );
          transitionCallback.fireException( ex );
        }
      }
    };
    
    final TransitionAction<Component> enableTransition = new TransitionAction<Component>( ) {
      @Override
      public void leave( Component parent, Completion transitionCallback ) {
        try {
          if ( State.NOTREADY.equals( ComponentState.this.stateMachine.getState( ) ) ) {
            parent.getBootstrapper( ).check( );
            if ( parent.getBuilder( ) != null && parent.getLocalService( ) != null ) {
              parent.getBuilder( ).fireCheck( parent.getLocalService( ).getServiceConfiguration( ) );
            }
          }
          parent.getBootstrapper( ).enable( );
          if ( parent.getBuilder( ) != null && parent.getLocalService( ) != null ) {
            parent.getBuilder( ).fireEnable( parent.getLocalService( ).getServiceConfiguration( ) );
          }
          transitionCallback.fire( );
        } catch ( Throwable ex ) {
          LOG.error( "Transition failed on " + parent.getName( ) + " due to " + ex.toString( ), ex );
          ComponentState.this.details.add( ex.toString( ) );
          transitionCallback.fireException( ex );
        }
      }
    };
    
    final TransitionAction<Component> disableTransition = new TransitionAction<Component>( ) {
      @Override
      public void leave( Component parent, Completion transitionCallback ) {
        try {
          parent.getBootstrapper( ).disable( );
          parent.getBuilder( ).fireDisable( parent.getLocalService( ).getServiceConfiguration( ) );
          transitionCallback.fire( );
        } catch ( Throwable ex ) {
          LOG.error( "Transition failed on " + parent.getName( ) + " due to " + ex.toString( ), ex );
          ComponentState.this.details.add( ex.toString( ) );
          transitionCallback.fireException( ex );
        }
      }
    };
    
    final TransitionAction<Component> stopTransition = new TransitionAction<Component>( ) {
      @Override
      public void leave( Component parent, Completion transitionCallback ) {
        try {
          parent.getBootstrapper( ).stop( );
          if ( parent.getBuilder( ) != null && parent.getLocalService( ) != null ) {
            parent.getBuilder( ).fireStop( parent.getLocalService( ).getServiceConfiguration( ) );
          }
          transitionCallback.fire( );
        } catch ( Throwable ex ) {
          LOG.error( "Transition failed on " + parent.getName( ) + " due to " + ex.toString( ), ex );
          ComponentState.this.details.add( ex.toString( ) );
          transitionCallback.fireException( ex );
        }
      }
    };
    
    final TransitionAction<Component> checkTransition = new TransitionAction<Component>( ) {
      @Override
      public void leave( Component parent, Completion transitionCallback ) {
        try {
          if ( State.LOADED.ordinal( ) < ComponentState.this.stateMachine.getState( ).ordinal( ) ) {
            parent.getBootstrapper( ).check( );
            if ( parent.getBuilder( ) != null && parent.getLocalService( ) != null ) {
              parent.getBuilder( ).fireCheck( parent.getLocalService( ).getServiceConfiguration( ) );
            }
          }
          transitionCallback.fire( );
        } catch ( Throwable ex ) {
          LOG.error( "Transition failed on " + parent.getName( ) + " due to " + ex.toString( ), ex );
          ComponentState.this.details.add( ex.toString( ) );
          if ( State.ENABLED.equals( ComponentState.this.stateMachine.getState( ) ) ) {
            try {
              parent.getBootstrapper( ).disable( );
              if ( parent.getBuilder( ) != null && parent.getLocalService( ) != null ) {
                parent.getBuilder( ).fireDisable( parent.getLocalService( ).getServiceConfiguration( ) );
              }
            } catch ( ServiceRegistrationException ex1 ) {
              LOG.error( ex1, ex1 );
            }
          }
          transitionCallback.fireException( ex );
        }
      }
    };
    
    final TransitionAction<Component> destroyTransition = new TransitionAction<Component>( ) {
      @Override
      public void leave( Component parent, Completion transitionCallback ) {
        try {
          parent.getBootstrapper( ).destroy( );
          transitionCallback.fire( );
        } catch ( Throwable ex ) {
          LOG.error( "Transition failed on " + parent.getName( ) + " due to " + ex.toString( ), ex );
          ComponentState.this.details.add( ex.toString( ) );
          transitionCallback.fireException( ex );
        }
      }
    };
    
    final TransitionListener<Component> addPipelines = Transitions.createListener( new Predicate<Component>( ) {
      @Override
      public boolean apply( Component arg0 ) {
        PipelineRegistry.getInstance( ).enable( arg0.getComponentId( ) );
        return true;
      }
    } );
    final TransitionListener<Component> removePipelines = Transitions.createListener( new Predicate<Component>( ) {
      @Override
      public boolean apply( Component arg0 ) {
        PipelineRegistry.getInstance( ).disable( arg0.getComponentId( ) );
        return true;
      }
    } );
    
    return new StateMachineBuilder<Component, State, Transition>( this.parent, State.PRIMORDIAL ) {
      {
        on( Transition.INITIALIZING ).from( State.PRIMORDIAL ).to( State.INITIALIZED ).error( State.BROKEN ).noop( );
        on( Transition.LOADING ).from( State.INITIALIZED ).to( State.LOADED ).error( State.BROKEN ).run( loadTransition );
        on( Transition.STARTING ).from( State.LOADED ).to( State.NOTREADY ).error( State.BROKEN ).run( startTransition );
        on( Transition.ENABLING ).from( State.DISABLED ).to( State.ENABLED ).error( State.NOTREADY ).add( addPipelines ).run( enableTransition );
        on( Transition.DISABLING ).from( State.ENABLED ).to( State.DISABLED ).error( State.NOTREADY ).add( removePipelines ).run( disableTransition );
        on( Transition.STOPPING ).from( State.DISABLED ).to( State.STOPPED ).error( State.NOTREADY ).run( stopTransition );
        on( Transition.DESTROYING ).from( State.STOPPED ).to( State.LOADED ).error( State.BROKEN ).run( destroyTransition );
        on( Transition.READY_CHECK ).from( State.NOTREADY ).to( State.DISABLED ).error( State.NOTREADY ).run( checkTransition );
        on( Transition.DISABLED_CHECK ).from( State.DISABLED ).to( State.DISABLED ).error( State.NOTREADY ).run( checkTransition );
        on( Transition.ENABLED_CHECK ).from( State.ENABLED ).to( State.ENABLED ).error( State.NOTREADY ).run( checkTransition );
      }
    }.newAtomicState( );
  }
  
  public CheckedListenableFuture<Component> transition( Transition transition ) throws IllegalStateException, NoSuchElementException, ExistingTransitionException {
    try {
      return this.stateMachine.startTransition( transition );
    } catch ( IllegalStateException ex ) {
      throw Exceptions.trace( ex );
    } catch ( NoSuchElementException ex ) {
      throw Exceptions.trace( ex );
    } catch ( ExistingTransitionException ex ) {
      throw ex;
    } catch ( Throwable ex ) {
      throw Exceptions.trace( new RuntimeException( "Failed to perform service transition " + transition + " for " + this.parent.getName( ) + ".\nCAUSE: "
                                                    + ex.getMessage( ) + "\nSTATE: " + this.stateMachine.toString( ), ex ) );
    }
  }
  
  public CheckedListenableFuture<Component> transition( State state ) throws IllegalStateException, NoSuchElementException, ExistingTransitionException {
    try {
      return this.stateMachine.startTransitionTo( state );
    } catch ( IllegalStateException ex ) {
      throw Exceptions.trace( ex );
    } catch ( NoSuchElementException ex ) {
      throw Exceptions.trace( ex );
    } catch ( ExistingTransitionException ex ) {
      throw ex;
    } catch ( Throwable ex ) {
      throw Exceptions.trace( new RuntimeException( "Failed to perform transition from " + this.getState( ) + " to " + state + " for " + this.parent.getName( )
                                                    + ".\nCAUSE: " + ex.getMessage( ) + "\nSTATE: " + this.stateMachine.toString( ), ex ) );
    }
  }
  
  public CheckedListenableFuture<Component> transitionSelf( ) {
    try {
      if ( this.checkTransition( Transition.READY_CHECK ) ) {//this is a special case of a transition which does not return to itself on a successful check
        return this.transition( Transition.READY_CHECK );
      } else {
        return this.transition( this.getState( ) );
      }
    } catch ( IllegalStateException ex ) {
      LOG.error( Exceptions.filterStackTrace( ex ) );
    } catch ( NoSuchElementException ex ) {
      LOG.error( Exceptions.filterStackTrace( ex ) );
    } catch ( ExistingTransitionException ex ) {
      LOG.error( Exceptions.filterStackTrace( ex ) );
    }
    return Futures.predestinedFuture( this.parent );
  }
  
  /**
   * @return the goal
   */
  public Component.State getGoal( ) {
    return this.goal;
  }
  
  void setGoal( Component.State goal ) {
    this.goal = goal;
  }
  
  public boolean isBusy( ) {
    return this.stateMachine.isBusy( );
  }
  
  public boolean checkTransition( Transition transition ) {
    return this.stateMachine.isLegalTransition( transition );
  }
  
}
