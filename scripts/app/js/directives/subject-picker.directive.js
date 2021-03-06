(function() {
    'use strict';

    AngularExtensions.addModuleConfig(function(module) {
        module.directive('subjectPicker', function() {
            return {
                scope: {
                    ngModel: '=',
                    ngChange: '&',
                    lesson: "=",
                    homework: "="
                },
                transclude: true,
                replace: true,
                restrict: 'E',
                templateUrl: 'diary/public/template/subject-picker.html',
                link: function(scope, element) {
                    var sortBySubjectLabel = function(a, b) {
                        if (a.label > b.label)
                            return 1;
                        if (a.label < b.label)
                            return -1;
                        return 0;
                    };

                    scope.search = null;
                    scope.displaySearch = false;

                    // init suggested subjects with all subjects
                    scope.suggestedSubjects = [];

                    // custom subject collection
                    // containing base subject collection + current ones being created by used
                    var subjects = [];

                    /*model.subjects.all.forEach(function(subject) {
                        subjects.push(subject);
                    });

                    subjects.sort(sortBySubjectLabel);
                    */

                    var setNewSubject = function(subjectLabel) {

                        if (!subjectLabel) {
                            return;
                        }

                        subjectLabel = subjectLabel.trim();

                        var existingSubject = null;

                        for (var i = 0; i < subjects.length; i++) {
                            if (sansAccent(subjects[i].label).toUpperCase() === sansAccent(subjectLabel).toUpperCase()) {
                                existingSubject = subjects[i];
                            }
                        }

                        if (!existingSubject) {
                            scope.ngModel = new Subject();
                            scope.ngModel.label = subjectLabel;
                            scope.ngModel.id = null;

                            if (scope.lesson && scope.lesson.audience){
                                scope.ngModel.school_id = scope.lesson.audience.structureId;
                            }else if (scope.homework && scope.homework.audience){
                                scope.ngModel.school_id = scope.homework.audience.structureId ;
                            }
                            if (!scope.ngModel.school_id){
                                scope.ngModel.school_id = model.me.structures[0];
                            }
                            //scope.ngModel.school_id = scope.lesson ? scope.lesson.audience.structureId : scope.homework && scope.homework.audience ?scope.homework.audience.structureId : undefined;
                            scope.ngModel.teacher_id = model.me.userId;
                            subjects.push(scope.ngModel);
                        } else {
                            scope.ngModel = existingSubject;
                        }
                    };
                    scope.$watch('lesson.audience.structureId', function() {
                        if (scope.ngModel && scope.lesson && scope.lesson.audience && scope.lesson.audience.structureId) {
                            scope.ngModel.school_id = scope.lesson ? scope.lesson.audience.structureId : scope.homework.audience.structureId;
                        }
                    });
                    var initSuggestedSubjects = function() {
                        scope.suggestedSubjects = [];

                        subjects = [];

                        model.subjects.all.forEach(function(subject) {
                            subjects.push(subject);
                        });

                        subjects.sort(sortBySubjectLabel);

                        for (var i = 0; i < subjects.length; i++) {
                            scope.suggestedSubjects.push(subjects[i]);
                        }
                    };


                    scope.$watch(()=>{
                        return model.subjects ? model.subjects.length() : undefined;
                    },()=>{
                        initSuggestedSubjects();
                    });
                    scope.goToSearchMode = function() {
                        scope.displaySearch = true;
                        scope.search = '';
                        initSuggestedSubjects();
                    };

                    scope.isSelected = function(subject) {

                        if (scope.ngModel && subject) {
                            if (scope.ngModel.id) {
                                return scope.ngModel.id === subject.id;
                            }
                            // subject may not have id if it's new one
                            else {
                                return sansAccent(scope.ngModel.label) === sansAccent(subject.label);
                            }
                        } else {
                            return false;
                        }
                    };

                    /**
                     * Search subject from input by user
                     */
                    scope.searchSubject = function(event) {

                        if (event.type === 'keydown' && event.keyCode === 9) {
                            scope.displaySearch = false;

                            if (scope.search != '') {
                                setNewSubject(scope.search);
                            }
                            return;
                        }

                        scope.search = scope.search.trim();

                        if (scope.search != '') {
                            var matchingSubjects = model.findSubjectsByLabel(scope.search);
                            scope.suggestedSubjects = new Array();

                            for (var i = 0; i < matchingSubjects.length; i++) {
                                scope.suggestedSubjects.push(matchingSubjects[i]);
                            }

                        } else {
                            initSuggestedSubjects();
                        }
                    };

                    scope.selectSubject = function(subject) {
                        scope.ngModel = subject;
                        scope.displaySearch = false;
                        if (scope.lesson) {
                            scope.lesson.previousLessonsLoaded = false;
                        }
                    };

                    $(element.context.ownerDocument).click(function(event) {
                        if (!$(event.target).is("item-suggest") && !$(event.target).is("#remove-subject") && !$(event.target).is("#input-subject")) {
                            scope.displaySearch = false;

                            // new subject that will need to be created on lesson/homework save
                            if (scope.suggestedSubjects.length === 0) {
                                setNewSubject(scope.search);
                            }
                            scope.$apply();
                        }
                    });


                    // function handler(event) {
                    //         var isClickedElementChildOfPopup = element
                    //                 .find(event.target)
                    //                 .length > 0;
                    //
                    //         if (isClickedElementChildOfPopup)
                    //                 return;
                    //
                    //         scope.$apply(function() {
                    //             scope.displaySearch = false;
                    //             if (scope.suggestedSubjects.length === 0) {
                    //                 setNewSubject(scope.search);
                    //             }
                    //         });
                    // }
                    // $(document).bind('click',handler );
                    //
                    // //free on detraoy element & handlers
                    // scope.$on("$destroy", function() {
                    //         $(document).unbind('click',handler );
                    // });
                }
            };
        });
    });

})();
